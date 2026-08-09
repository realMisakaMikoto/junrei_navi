import { createHash } from "node:crypto";
import { performance } from "node:perf_hooks";
import { setTimeout as delay } from "node:timers/promises";
import type {
  Coordinate,
  MatrixRequest,
  NormalizedMatrix,
  NormalizedMatrixElement,
  NormalizedRoute,
  NormalizedRouteLeg,
  NormalizedRouteStep,
  NormalizedTransitDetails,
  RoadMode,
  RouteRequest,
  TransitRoutingPreference,
} from "../contract.js";
import { ApiError } from "../errors.js";
import type { UpstreamReservation } from "../quota/ledger.js";

export const AMAP_CONVERT_URL =
  "https://restapi.amap.com/v3/assistant/coordinate/convert";
export const AMAP_DISTANCE_URL = "https://restapi.amap.com/v3/distance";
export const AMAP_DRIVING_URL = "https://restapi.amap.com/v5/direction/driving";
export const AMAP_WALKING_URL = "https://restapi.amap.com/v5/direction/walking";
export const AMAP_BICYCLING_URL = "https://restapi.amap.com/v5/direction/bicycling";
export const AMAP_TRANSIT_URL =
  "https://restapi.amap.com/v5/direction/transit/integrated";
export const AMAP_REVERSE_GEOCODE_URL = "https://restapi.amap.com/v3/geocode/regeo";

const MAX_CONVERSION_BATCH_SIZE = 40;
const MAX_UPSTREAM_CONCURRENCY = 2;
const MAX_UPSTREAM_QUEUE = 64;
const DEFAULT_MAX_REQUEST_STARTS_PER_SECOND = 3;
const MAX_CONFIGURED_REQUEST_STARTS_PER_SECOND = 1_000;
const RATE_WINDOW_MILLIS = 1_000;
const DEFAULT_TIMEOUT_MILLIS = 8_000;
const MAX_TIMEOUT_MILLIS = 30_000;
const DEFAULT_MAX_RESPONSE_BYTES = 1_048_576;
const MAX_CONFIGURED_RESPONSE_BYTES = 2_097_152;
const MAX_POLYLINE_POINTS = 20_000;

export type AmapUsage = Readonly<{
  conversion: number;
  geocode: number;
  distance: number;
  route: number;
}>;

export type AmapRoutesClientOptions = Readonly<{
  apiKey: string;
  signingSecret: string;
  fetch?: typeof fetch;
  timeoutMillis?: number;
  maxResponseBytes?: number;
  now?: () => Date;
  requestStartsPerSecond?: number;
  requestStartClock?: () => number;
  requestStartSleep?: (milliseconds: number) => Promise<void>;
}>;

type CoordinateWithIndex = Readonly<{
  coordinate: Coordinate;
  index: number;
}>;

type TransitRouteRequest = Extract<RouteRequest, { mode: "TRANSIT" }>;

type StepWithGeometry = Readonly<{
  step: NormalizedRouteStep;
  points: Coordinate[];
}>;

type LegWithGeometry = Readonly<{
  leg: NormalizedRouteLeg;
  points: Coordinate[];
}>;

export function usageForMatrix(request: MatrixRequest): AmapUsage {
  validateMatrixRequest(request);
  const count = request.coordinates.length;
  return {
    conversion: Math.ceil(count / MAX_CONVERSION_BATCH_SIZE),
    geocode: 0,
    distance: request.mode === "DRIVE" ? count : 0,
    route: request.mode === "DRIVE" ? 0 : count * (count - 1),
  };
}

export function usageForRoute(request: RouteRequest): AmapUsage {
  validateRouteRequest(request);
  const count = request.locations.length;
  return {
    conversion: Math.ceil(count / MAX_CONVERSION_BATCH_SIZE),
    geocode: request.mode === "TRANSIT" ? count : 0,
    distance: 0,
    route: count - 1,
  };
}

export class AmapRoutesClient {
  private readonly fetchImplementation: typeof fetch;
  private readonly timeoutMillis: number;
  private readonly maxResponseBytes: number;
  private readonly now: () => Date;
  private readonly requestStartLimiter: SlidingWindowStartLimiter;
  private readonly fetchSemaphore = new AsyncSemaphore(
    MAX_UPSTREAM_CONCURRENCY,
    MAX_UPSTREAM_QUEUE,
  );

  constructor(private readonly options: AmapRoutesClientOptions) {
    if (!isSafeCredential(options.apiKey) || !isSafeCredential(options.signingSecret)) {
      throw new Error("AMap credentials are required");
    }
    this.fetchImplementation = options.fetch ?? fetch;
    this.timeoutMillis = boundedInteger(
      options.timeoutMillis ?? DEFAULT_TIMEOUT_MILLIS,
      1,
      MAX_TIMEOUT_MILLIS,
      "AMap timeout",
    );
    this.maxResponseBytes = boundedInteger(
      options.maxResponseBytes ?? DEFAULT_MAX_RESPONSE_BYTES,
      1,
      MAX_CONFIGURED_RESPONSE_BYTES,
      "AMap response limit",
    );
    this.now = options.now ?? (() => new Date());
    this.requestStartLimiter = new SlidingWindowStartLimiter(
      boundedInteger(
        options.requestStartsPerSecond ?? DEFAULT_MAX_REQUEST_STARTS_PER_SECOND,
        1,
        MAX_CONFIGURED_REQUEST_STARTS_PER_SECOND,
        "AMap request-start limit",
      ),
      options.requestStartClock ?? (() => performance.now()),
      options.requestStartSleep ?? ((milliseconds) => delay(milliseconds)),
    );
  }

  usageForMatrix(request: MatrixRequest): readonly UpstreamReservation[] {
    return toUpstreamReservations(usageForMatrix(request));
  }

  usageForRoute(request: RouteRequest): readonly UpstreamReservation[] {
    return toUpstreamReservations(usageForRoute(request));
  }

  async matrix(request: MatrixRequest): Promise<NormalizedMatrix> {
    usageForMatrix(request);
    const coordinates = await this.convertCoordinates(request.coordinates);
    const elements =
      request.mode === "DRIVE"
        ? await this.drivingMatrix(coordinates)
        : await this.pairwiseRouteMatrix(coordinates, request.mode);
    if (!elements.some((element) => element.originIndex !== element.destinationIndex && element.status === "OK")) {
      throw new ApiError("NO_ROUTE");
    }
    return { elements };
  }

  async route(request: RouteRequest): Promise<NormalizedRoute> {
    usageForRoute(request);
    const coordinates = await this.convertCoordinates(request.locations);
    if (request.mode === "DRIVE" || request.mode === "WALK" || request.mode === "BICYCLE") {
      const pairs = adjacentPairs(coordinates);
      const legs = await mapWithConcurrency(pairs, MAX_UPSTREAM_CONCURRENCY, ([origin, destination]) =>
        this.roadLeg([origin, destination], request.mode),
      );
      return combineLegs(legs);
    }

    const transitRequest = request as TransitRouteRequest;
    const cityCodes = await mapWithConcurrency(
      coordinates,
      MAX_UPSTREAM_CONCURRENCY,
      (coordinate) => this.reverseGeocodeCityCode(coordinate),
    );
    const departure = chinaDateAndTime(transitRequest.departureTime, this.now);
    const strategy = transitStrategy(transitRequest.transitRoutingPreference);
    const pairs = adjacentPairs(coordinates);
    const legs = await mapWithConcurrency(pairs, MAX_UPSTREAM_CONCURRENCY, ([origin, destination], index) => {
      const city1 = cityCodes[index];
      const city2 = cityCodes[index + 1];
      if (city1 === undefined || city2 === undefined) throw new ApiError("UPSTREAM_UNAVAILABLE");
      return this.transitLeg(origin, destination, city1, city2, departure, strategy);
    });
    return combineLegs(legs);
  }

  async convertCoordinates(coordinates: readonly Coordinate[]): Promise<Coordinate[]> {
    validateCoordinates(coordinates, 1, 400);
    const batches: Coordinate[][] = [];
    for (let index = 0; index < coordinates.length; index += MAX_CONVERSION_BATCH_SIZE) {
      batches.push(coordinates.slice(index, index + MAX_CONVERSION_BATCH_SIZE));
    }
    const converted = await mapWithConcurrency(
      batches,
      MAX_UPSTREAM_CONCURRENCY,
      (batch) => this.convertBatch(batch),
    );
    return converted.flat();
  }

  private async convertBatch(coordinates: readonly Coordinate[]): Promise<Coordinate[]> {
    if (coordinates.length === 0 || coordinates.length > MAX_CONVERSION_BATCH_SIZE) {
      throw new ApiError("INVALID_ARGUMENT");
    }
    const payload = await this.getJson(AMAP_CONVERT_URL, {
      coordsys: "gps",
      locations: coordinates.map(formatCoordinatePair).join("|"),
    });
    const locations = requireString(payload["locations"], 1, 16_384);
    const converted = locations.split(";").map(parseCoordinatePair);
    if (converted.length !== coordinates.length) throw new ApiError("UPSTREAM_UNAVAILABLE");
    return converted;
  }

  private async drivingMatrix(coordinates: readonly Coordinate[]): Promise<NormalizedMatrixElement[]> {
    const tasks = coordinates.map((destination, destinationIndex) => ({ destination, destinationIndex }));
    const columns = await mapWithConcurrency(
      tasks,
      MAX_UPSTREAM_CONCURRENCY,
      async ({ destination, destinationIndex }) => {
        const origins: CoordinateWithIndex[] = coordinates.flatMap((coordinate, index) =>
          index === destinationIndex ? [] : [{ coordinate, index }],
        );
        const payload = await this.getJson(AMAP_DISTANCE_URL, {
          destination: formatCoordinatePair(destination),
          origins: origins.map(({ coordinate }) => formatCoordinatePair(coordinate)).join("|"),
          type: "1",
        });
        const results = payload["results"];
        if (!Array.isArray(results) || results.length !== origins.length) {
          throw new ApiError("UPSTREAM_UNAVAILABLE");
        }
        return origins.map(({ index: originIndex }, resultIndex): NormalizedMatrixElement => {
          const result = requireRecord(results[resultIndex]);
          const distance = optionalAmapNumber(result["distance"]);
          const duration = optionalAmapNumber(result["duration"]);
          if (distance === undefined && duration === undefined) {
            return { originIndex, destinationIndex, status: "UNREACHABLE" };
          }
          if (distance === undefined || duration === undefined) throw new ApiError("UPSTREAM_UNAVAILABLE");
          return {
            originIndex,
            destinationIndex,
            status: "OK",
            distanceMeters: distance,
            durationSeconds: duration,
          };
        });
      },
    );

    const byPair = new Map<string, NormalizedMatrixElement>();
    for (const column of columns) {
      for (const element of column) byPair.set(matrixKey(element.originIndex, element.destinationIndex), element);
    }
    return matrixOrder(coordinates.length, byPair);
  }

  private async pairwiseRouteMatrix(
    coordinates: readonly Coordinate[],
    mode: Exclude<RoadMode, "DRIVE">,
  ): Promise<NormalizedMatrixElement[]> {
    const pairs: Array<Readonly<{ origin: Coordinate; destination: Coordinate; originIndex: number; destinationIndex: number }>> = [];
    for (let originIndex = 0; originIndex < coordinates.length; originIndex += 1) {
      for (let destinationIndex = 0; destinationIndex < coordinates.length; destinationIndex += 1) {
        if (originIndex === destinationIndex) continue;
        const origin = coordinates[originIndex];
        const destination = coordinates[destinationIndex];
        if (origin === undefined || destination === undefined) throw new ApiError("UPSTREAM_UNAVAILABLE");
        pairs.push({ origin, destination, originIndex, destinationIndex });
      }
    }
    const routed = await mapWithConcurrency(pairs, MAX_UPSTREAM_CONCURRENCY, async (pair) => {
      try {
        const leg = await this.roadLeg([pair.origin, pair.destination], mode);
        return {
          originIndex: pair.originIndex,
          destinationIndex: pair.destinationIndex,
          status: "OK",
          distanceMeters: leg.leg.distanceMeters,
          durationSeconds: leg.leg.durationSeconds,
        } satisfies NormalizedMatrixElement;
      } catch (error) {
        if (error instanceof ApiError && error.code === "NO_ROUTE") {
          return {
            originIndex: pair.originIndex,
            destinationIndex: pair.destinationIndex,
            status: "UNREACHABLE",
          } satisfies NormalizedMatrixElement;
        }
        throw error;
      }
    });
    const byPair = new Map(routed.map((element) => [matrixKey(element.originIndex, element.destinationIndex), element]));
    return matrixOrder(coordinates.length, byPair);
  }

  private async roadLeg(coordinates: readonly Coordinate[], mode: RoadMode): Promise<LegWithGeometry> {
    const origin = coordinates[0];
    const destination = coordinates.at(-1);
    if (origin === undefined || destination === undefined || coordinates.length !== 2) {
      throw new ApiError("INVALID_ARGUMENT");
    }
    const parameters: Record<string, string> = {
      destination: formatCoordinatePair(destination),
      origin: formatCoordinatePair(origin),
      show_fields: "cost,polyline,navi",
    };
    if (mode === "DRIVE") {
      parameters["strategy"] = "0";
    }
    const payload = await this.getJson(roadUrl(mode), parameters);
    return normalizeRoadLeg(payload, mode);
  }

  private async reverseGeocodeCityCode(coordinate: Coordinate): Promise<string> {
    const payload = await this.getJson(AMAP_REVERSE_GEOCODE_URL, {
      extensions: "base",
      location: formatCoordinatePair(coordinate),
      radius: "1000",
    });
    const regeocode = requireRecord(payload["regeocode"]);
    const addressComponent = requireRecord(regeocode["addressComponent"]);
    const cityCode = addressComponent["citycode"];
    if (typeof cityCode === "string" && /^\d{3,6}$/.test(cityCode)) return cityCode;
    const adcode = requireString(addressComponent["adcode"], 6, 6);
    if (!/^\d{6}$/.test(adcode)) throw new ApiError("UPSTREAM_UNAVAILABLE");
    return adcode;
  }

  private async transitLeg(
    origin: Coordinate,
    destination: Coordinate,
    city1: string,
    city2: string,
    departure: Readonly<{ date: string; time: string }>,
    strategy: string,
  ): Promise<LegWithGeometry> {
    const payload = await this.getJson(AMAP_TRANSIT_URL, {
      city1,
      city2,
      date: departure.date,
      destination: formatCoordinatePair(destination),
      origin: formatCoordinatePair(origin),
      show_fields: "cost,polyline",
      strategy,
      time: departure.time,
    });
    return normalizeTransitLeg(payload);
  }

  private async getJson(endpoint: string, parameters: Readonly<Record<string, string>>): Promise<Record<string, unknown>> {
    let url: string;
    try {
      url = signedUrl(endpoint, parameters, this.options.apiKey, this.options.signingSecret);
    } catch {
      throw new ApiError("BACKEND_UNAVAILABLE");
    }
    const release = await this.fetchSemaphore.acquire(this.timeoutMillis);
    try {
      await this.requestStartLimiter.acquire();
      let response: Response;
      try {
        response = await this.fetchImplementation(url, {
          method: "GET",
          headers: { accept: "application/json" },
          redirect: "error",
          signal: AbortSignal.timeout(this.timeoutMillis),
        });
      } catch {
        throw new ApiError("UPSTREAM_UNAVAILABLE");
      }
      if (!response.ok) {
        await cancelBody(response);
        if (response.status === 429) throw new ApiError("RATE_LIMITED");
        throw new ApiError("UPSTREAM_UNAVAILABLE");
      }
      const payload = await readBoundedJson(response, this.maxResponseBytes);
      const record = requireRecord(payload);
      if (record["status"] !== "1" || record["infocode"] !== "10000") {
        throw amapApiError(record["infocode"]);
      }
      return record;
    } finally {
      release();
    }
  }
}

function normalizeRoadLeg(payload: Record<string, unknown>, mode: RoadMode): LegWithGeometry {
  const route = requireRecord(payload["route"]);
  const paths = route["paths"];
  if (!Array.isArray(paths)) throw new ApiError("UPSTREAM_UNAVAILABLE");
  if (paths.length === 0) throw new ApiError("NO_ROUTE");
  const path = requireRecord(paths[0]);
  const cost = requireRecord(path["cost"]);
  const distanceMeters = requireAmapNumber(path["distance"]);
  const durationSeconds = requireAmapNumber(cost["duration"]);
  const stepsValue = path["steps"];
  if (!Array.isArray(stepsValue)) throw new ApiError("UPSTREAM_UNAVAILABLE");
  const steps = stepsValue.map((value) => normalizeRoadStep(value, mode));
  const points = mergeGeometry(steps.map(({ points: stepPoints }) => stepPoints));
  requireGeometryForNontrivialLeg(distanceMeters, durationSeconds, points);
  const legBase: NormalizedRouteLeg = {
    distanceMeters,
    durationSeconds,
    steps: steps.map(({ step }) => step),
  };
  return {
    leg: withEncodedPolyline(legBase, points),
    points,
  };
}

function toUpstreamReservations(usage: AmapUsage): readonly UpstreamReservation[] {
  const buckets: ReadonlyArray<keyof AmapUsage> = ["conversion", "geocode", "distance", "route"];
  return buckets.flatMap((bucket) => usage[bucket] === 0 ? [] : [{ bucket, units: usage[bucket] }]);
}

function normalizeRoadStep(value: unknown, mode: RoadMode): StepWithGeometry {
  const record = requireRecord(value);
  const cost = optionalRecord(record["cost"]);
  const distanceMeters = optionalAmapNumber(record["step_distance"] ?? record["distance"]) ?? 0;
  const durationSeconds = optionalAmapNumber(cost?.["duration"] ?? record["duration"]) ?? 0;
  const points = optionalPolyline(record["polyline"]);
  const instruction = optionalSafeString(record["instruction"], 512);
  const maneuver = optionalSafeString(record["action"] ?? record["assistant_action"], 128);
  const base: NormalizedRouteStep = {
    travelMode: mode,
    distanceMeters,
    durationSeconds,
  };
  return {
    step: {
      ...withEncodedPolyline(base, points),
      ...(instruction === undefined ? {} : { instruction }),
      ...(maneuver === undefined ? {} : { maneuver }),
    },
    points,
  };
}

function normalizeTransitLeg(payload: Record<string, unknown>): LegWithGeometry {
  const route = requireRecord(payload["route"]);
  const transits = route["transits"];
  if (!Array.isArray(transits)) throw new ApiError("UPSTREAM_UNAVAILABLE");
  if (transits.length === 0) throw new ApiError("NO_ROUTE");
  const transit = requireRecord(transits[0]);
  const cost = requireRecord(transit["cost"]);
  const distanceMeters = requireAmapNumber(transit["distance"]);
  const durationSeconds = requireAmapNumber(cost["duration"]);
  const segments = transit["segments"];
  if (!Array.isArray(segments)) throw new ApiError("UPSTREAM_UNAVAILABLE");

  const normalizedSteps: StepWithGeometry[] = [];
  for (const value of segments) {
    const segment = requireRecord(value);
    normalizedSteps.push(...normalizeTransitWalking(segment["walking"]));
    normalizedSteps.push(...normalizeTransitBus(segment["bus"]));
    const railway = normalizeTransitRailway(segment["railway"]);
    if (railway !== undefined) normalizedSteps.push(railway);
    const taxi = normalizeTransitTaxi(segment["taxi"]);
    if (taxi !== undefined) normalizedSteps.push(taxi);
  }
  if (!normalizedSteps.some(({ step }) => step.travelMode === "TRANSIT")) {
    throw new ApiError("NO_ROUTE");
  }
  const points = mergeGeometry(normalizedSteps.map(({ points: stepPoints }) => stepPoints));
  requireGeometryForNontrivialLeg(distanceMeters, durationSeconds, points);
  const legBase: NormalizedRouteLeg = {
    distanceMeters,
    durationSeconds,
    steps: normalizedSteps.map(({ step }) => step),
  };
  return {
    leg: withEncodedPolyline(legBase, points),
    points,
  };
}

function normalizeTransitWalking(value: unknown): StepWithGeometry[] {
  const walking = optionalRecord(value);
  if (walking === undefined || Object.keys(walking).length === 0) return [];
  const steps = walking["steps"];
  if (Array.isArray(steps) && steps.length > 0) {
    return steps.map((step) => normalizeRoadStep(step, "WALK"));
  }
  const distanceMeters = optionalAmapNumber(walking["distance"]) ?? 0;
  const durationSeconds = optionalAmapNumber(walking["duration"] ?? optionalRecord(walking["cost"])?.["duration"]) ?? 0;
  if (distanceMeters === 0 && durationSeconds === 0) return [];
  return [{ step: { travelMode: "WALK", distanceMeters, durationSeconds }, points: [] }];
}

function normalizeTransitBus(value: unknown): StepWithGeometry[] {
  const bus = optionalRecord(value);
  if (bus === undefined || Object.keys(bus).length === 0) return [];
  const buslines = bus["buslines"];
  if (!Array.isArray(buslines)) throw new ApiError("UPSTREAM_UNAVAILABLE");
  return buslines.map((lineValue) => {
    const line = requireRecord(lineValue);
    const distanceMeters = optionalAmapNumber(line["distance"]) ?? 0;
    const durationSeconds = optionalAmapNumber(line["duration"] ?? optionalRecord(line["cost"])?.["duration"]) ?? 0;
    const points = optionalPolyline(line["polyline"]);
    const departureStop = stopName(line["start_stop"]);
    const arrivalStop = stopName(line["end_stop"]);
    const lineName = optionalSafeString(line["name"], 256);
    const vehicleType = normalizeVehicleType(line["type"]);
    const transit: NormalizedTransitDetails = compactTransitDetails({
      departureStop,
      arrivalStop,
      lineName,
      vehicleType,
    });
    const base: NormalizedRouteStep = {
      travelMode: "TRANSIT",
      distanceMeters,
      durationSeconds,
      ...(Object.keys(transit).length === 0 ? {} : { transit }),
    };
    return { step: withEncodedPolyline(base, points), points };
  });
}

function normalizeTransitRailway(value: unknown): StepWithGeometry | undefined {
  const railway = optionalRecord(value);
  if (railway === undefined || Object.keys(railway).length === 0) return undefined;
  const cost = optionalRecord(railway["cost"]);
  const distanceMeters = optionalAmapNumber(railway["distance"]) ?? 0;
  const durationSeconds = optionalAmapNumber(railway["duration"] ?? cost?.["duration"] ?? railway["time"]) ?? 0;
  const points = optionalPolyline(railway["polyline"]);
  const transit = compactTransitDetails({
    departureStop: stopName(railway["departure_stop"]),
    arrivalStop: stopName(railway["arrival_stop"]),
    lineName: optionalSafeString(railway["trip"], 256),
    vehicleType: "TRAIN",
  });
  const base: NormalizedRouteStep = {
    travelMode: "TRANSIT",
    distanceMeters,
    durationSeconds,
    transit,
  };
  return { step: withEncodedPolyline(base, points), points };
}

function normalizeTransitTaxi(value: unknown): StepWithGeometry | undefined {
  const taxi = optionalRecord(value);
  if (taxi === undefined || Object.keys(taxi).length === 0) return undefined;
  const cost = optionalRecord(taxi["cost"]);
  const distanceMeters = optionalAmapNumber(taxi["distance"]) ?? 0;
  const durationSeconds = optionalAmapNumber(taxi["duration"] ?? cost?.["duration"]) ?? 0;
  const points = optionalPolyline(taxi["polyline"]);
  return {
    step: withEncodedPolyline(
      { travelMode: "DRIVE", distanceMeters, durationSeconds },
      points,
    ),
    points,
  };
}

function combineLegs(legs: readonly LegWithGeometry[]): NormalizedRoute {
  if (legs.length === 0) throw new ApiError("NO_ROUTE");
  const distanceMeters = sumFinite(legs.map(({ leg }) => leg.distanceMeters));
  const durationSeconds = sumFinite(legs.map(({ leg }) => leg.durationSeconds));
  const points = mergeGeometry(legs.map(({ points: legPoints }) => legPoints));
  const base: NormalizedRoute = {
    distanceMeters,
    durationSeconds,
    legs: legs.map(({ leg }) => leg),
  };
  return withEncodedPolyline(base, points);
}

function compactTransitDetails(values: {
  departureStop: string | undefined;
  arrivalStop: string | undefined;
  lineName: string | undefined;
  vehicleType: string | undefined;
}): NormalizedTransitDetails {
  return Object.fromEntries(Object.entries(values).filter(([, value]) => value !== undefined)) as NormalizedTransitDetails;
}

function stopName(value: unknown): string | undefined {
  const stop = optionalRecord(value);
  return stop === undefined ? undefined : optionalSafeString(stop["name"], 256);
}

function normalizeVehicleType(value: unknown): string | undefined {
  const type = optionalSafeString(value, 128);
  if (type === undefined) return undefined;
  if (type.includes("地铁")) return "SUBWAY";
  if (type.includes("轻轨")) return "LIGHT_RAIL";
  if (type.includes("铁路") || type.includes("火车") || type.includes("高铁")) return "TRAIN";
  if (type.includes("公交") || type.includes("巴士") || /bus/i.test(type)) return "BUS";
  return undefined;
}

function matrixOrder(
  size: number,
  byPair: ReadonlyMap<string, NormalizedMatrixElement>,
): NormalizedMatrixElement[] {
  const elements: NormalizedMatrixElement[] = [];
  for (let originIndex = 0; originIndex < size; originIndex += 1) {
    for (let destinationIndex = 0; destinationIndex < size; destinationIndex += 1) {
      if (originIndex === destinationIndex) {
        elements.push({
          originIndex,
          destinationIndex,
          status: "OK",
          distanceMeters: 0,
          durationSeconds: 0,
        });
        continue;
      }
      const element = byPair.get(matrixKey(originIndex, destinationIndex));
      if (element === undefined) throw new ApiError("UPSTREAM_UNAVAILABLE");
      elements.push(element);
    }
  }
  return elements;
}

function matrixKey(originIndex: number, destinationIndex: number): string {
  return `${originIndex}:${destinationIndex}`;
}

function roadUrl(mode: RoadMode): string {
  if (mode === "DRIVE") return AMAP_DRIVING_URL;
  if (mode === "WALK") return AMAP_WALKING_URL;
  return AMAP_BICYCLING_URL;
}

function transitStrategy(preference: TransitRoutingPreference | undefined): string {
  if (preference === "LESS_WALKING") return "3";
  if (preference === "FEWER_TRANSFERS") return "2";
  return "0";
}

function chinaDateAndTime(
  departureTime: string | undefined,
  now: () => Date,
): Readonly<{ date: string; time: string }> {
  const instant = departureTime === undefined ? now() : new Date(departureTime);
  const milliseconds = instant.getTime();
  if (!Number.isFinite(milliseconds)) throw new ApiError("INVALID_ARGUMENT");
  const chinaTime = new Date(milliseconds + 8 * 60 * 60 * 1_000).toISOString();
  return { date: chinaTime.slice(0, 10), time: chinaTime.slice(11, 16) };
}

function adjacentPairs<T>(values: readonly T[]): Array<readonly [T, T]> {
  const pairs: Array<readonly [T, T]> = [];
  for (let index = 0; index + 1 < values.length; index += 1) {
    const origin = values[index];
    const destination = values[index + 1];
    if (origin === undefined || destination === undefined) throw new ApiError("UPSTREAM_UNAVAILABLE");
    pairs.push([origin, destination]);
  }
  return pairs;
}

function signedUrl(
  endpoint: string,
  parameters: Readonly<Record<string, string>>,
  apiKey: string,
  signingSecret: string,
): string {
  const unsignedParameters = { ...parameters, key: apiKey, output: "json" };
  const entries = Object.entries(unsignedParameters).sort(([left], [right]) =>
    left < right ? -1 : left > right ? 1 : 0,
  );
  const canonicalQuery = entries.map(([key, value]) => `${key}=${value}`).join("&");
  const signature = createHash("md5").update(canonicalQuery + signingSecret, "utf8").digest("hex");
  const url = new URL(endpoint);
  for (const [key, value] of entries) url.searchParams.append(key, value);
  url.searchParams.append("sig", signature);
  return url.toString();
}

async function readBoundedJson(response: Response, maxBytes: number): Promise<unknown> {
  const contentLength = response.headers.get("content-length");
  if (contentLength !== null) {
    if (!/^\d+$/.test(contentLength) || Number(contentLength) > maxBytes) {
      await cancelBody(response);
      throw new ApiError("UPSTREAM_UNAVAILABLE");
    }
  }
  if (response.body === null) throw new ApiError("UPSTREAM_UNAVAILABLE");
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let totalBytes = 0;
  try {
    while (true) {
      const result = await reader.read();
      if (result.done) break;
      totalBytes += result.value.byteLength;
      if (totalBytes > maxBytes) {
        await reader.cancel();
        throw new ApiError("UPSTREAM_UNAVAILABLE");
      }
      chunks.push(result.value);
    }
    const bytes = new Uint8Array(totalBytes);
    let offset = 0;
    for (const chunk of chunks) {
      bytes.set(chunk, offset);
      offset += chunk.byteLength;
    }
    const text = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
    return JSON.parse(text) as unknown;
  } catch (error) {
    if (error instanceof ApiError) throw error;
    throw new ApiError("UPSTREAM_UNAVAILABLE");
  } finally {
    reader.releaseLock();
  }
}

async function cancelBody(response: Response): Promise<void> {
  try {
    await response.body?.cancel();
  } catch {
    // The body is intentionally ignored for safe upstream error mapping.
  }
}

function amapApiError(value: unknown): ApiError {
  if (typeof value !== "string") return new ApiError("UPSTREAM_UNAVAILABLE");
  if (["10003", "10029", "10044", "10045", "40000", "40003"].includes(value)) {
    return new ApiError("QUOTA_EXHAUSTED");
  }
  if (["10004", "10014", "10015", "10019", "10020", "10021"].includes(value)) {
    return new ApiError("RATE_LIMITED");
  }
  if (["20800", "20801", "20802", "20803"].includes(value)) return new ApiError("NO_ROUTE");
  return new ApiError("UPSTREAM_UNAVAILABLE");
}

function optionalPolyline(value: unknown): Coordinate[] {
  if (value === undefined || value === "") return [];
  const polyline = requireString(value, 1, 512_000);
  const pairs = polyline.split(";");
  if (pairs.length > MAX_POLYLINE_POINTS) throw new ApiError("UPSTREAM_UNAVAILABLE");
  return pairs.map(parseCoordinatePair);
}

function parseCoordinatePair(value: string): Coordinate {
  const pieces = value.split(",");
  if (pieces.length !== 2) throw new ApiError("UPSTREAM_UNAVAILABLE");
  const longitude = parseCoordinateNumber(pieces[0], -180, 180);
  const latitude = parseCoordinateNumber(pieces[1], -90, 90);
  return { latitude, longitude };
}

function parseCoordinateNumber(value: string | undefined, minimum: number, maximum: number): number {
  if (value === undefined || !/^-?(?:0|[1-9]\d{0,2})(?:\.\d{1,12})?$/.test(value)) {
    throw new ApiError("UPSTREAM_UNAVAILABLE");
  }
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed < minimum || parsed > maximum) {
    throw new ApiError("UPSTREAM_UNAVAILABLE");
  }
  return parsed;
}

function mergeGeometry(groups: readonly (readonly Coordinate[])[]): Coordinate[] {
  const merged: Coordinate[] = [];
  for (const group of groups) {
    for (const point of group) {
      const previous = merged.at(-1);
      if (previous?.latitude === point.latitude && previous.longitude === point.longitude) continue;
      merged.push(point);
      if (merged.length > MAX_POLYLINE_POINTS) throw new ApiError("UPSTREAM_UNAVAILABLE");
    }
  }
  return merged;
}

function withEncodedPolyline<T extends object>(base: T, points: readonly Coordinate[]): T & { encodedPolyline?: string } {
  if (points.length === 0) return base;
  return { ...base, encodedPolyline: encodePolyline(points) };
}

function encodePolyline(points: readonly Coordinate[]): string {
  let previousLatitude = 0;
  let previousLongitude = 0;
  let encoded = "";
  for (const point of points) {
    const latitude = Math.round(point.latitude * 100_000);
    const longitude = Math.round(point.longitude * 100_000);
    encoded += encodePolylineDelta(latitude - previousLatitude);
    encoded += encodePolylineDelta(longitude - previousLongitude);
    previousLatitude = latitude;
    previousLongitude = longitude;
  }
  return encoded;
}

function encodePolylineDelta(delta: number): string {
  let value = delta < 0 ? -delta * 2 - 1 : delta * 2;
  let encoded = "";
  while (value >= 0x20) {
    encoded += String.fromCharCode((value % 0x20) + 0x20 + 63);
    value = Math.floor(value / 0x20);
  }
  return encoded + String.fromCharCode(value + 63);
}

function validateMatrixRequest(request: MatrixRequest): void {
  if (request.mode !== "DRIVE" && request.mode !== "WALK" && request.mode !== "BICYCLE") {
    throw new ApiError("INVALID_ARGUMENT");
  }
  if (request.objective !== "FASTEST" && request.objective !== "SHORTEST") {
    throw new ApiError("INVALID_ARGUMENT");
  }
  validateCoordinates(request.coordinates, 2, 10);
}

function validateRouteRequest(request: RouteRequest): void {
  if (request.mode !== "DRIVE" && request.mode !== "WALK" && request.mode !== "BICYCLE" && request.mode !== "TRANSIT") {
    throw new ApiError("INVALID_ARGUMENT");
  }
  validateCoordinates(request.locations, 2, 12);
  if (request.mode === "TRANSIT") {
    if (request.arrivalTime !== undefined || request.transitTravelModes !== undefined) {
      throw new ApiError("INVALID_ARGUMENT");
    }
    if (
      request.transitRoutingPreference !== undefined &&
      request.transitRoutingPreference !== "LESS_WALKING" &&
      request.transitRoutingPreference !== "FEWER_TRANSFERS"
    ) {
      throw new ApiError("INVALID_ARGUMENT");
    }
    if (request.departureTime !== undefined && !isRfc3339(request.departureTime)) {
      throw new ApiError("INVALID_ARGUMENT");
    }
  }
}

function validateCoordinates(coordinates: readonly Coordinate[], minimum: number, maximum: number): void {
  if (!Array.isArray(coordinates) || coordinates.length < minimum || coordinates.length > maximum) {
    throw new ApiError("INVALID_ARGUMENT");
  }
  for (const coordinate of coordinates) {
    if (
      typeof coordinate !== "object" ||
      coordinate === null ||
      !Number.isFinite(coordinate.latitude) ||
      !Number.isFinite(coordinate.longitude) ||
      coordinate.latitude < -90 ||
      coordinate.latitude > 90 ||
      coordinate.longitude < -180 ||
      coordinate.longitude > 180
    ) {
      throw new ApiError("INVALID_ARGUMENT");
    }
  }
}

function isRfc3339(value: string): boolean {
  return /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/.test(value) &&
    Number.isFinite(Date.parse(value));
}

function formatCoordinatePair(coordinate: Coordinate): string {
  return `${formatCoordinate(coordinate.longitude)},${formatCoordinate(coordinate.latitude)}`;
}

function formatCoordinate(value: number): string {
  const normalized = Object.is(value, -0) ? 0 : value;
  return normalized.toFixed(6).replace(/(?:\.0+|(?:(\.\d*?)0+))$/, "$1");
}

function requireAmapNumber(value: unknown): number {
  const parsed = optionalAmapNumber(value);
  if (parsed === undefined) throw new ApiError("UPSTREAM_UNAVAILABLE");
  return parsed;
}

function optionalAmapNumber(value: unknown): number | undefined {
  if (value === undefined || value === "") return undefined;
  if (typeof value === "number") {
    if (Number.isFinite(value) && value >= 0 && value <= Number.MAX_SAFE_INTEGER) return value;
    throw new ApiError("UPSTREAM_UNAVAILABLE");
  }
  if (typeof value !== "string" || !/^\d+(?:\.\d+)?$/.test(value)) {
    throw new ApiError("UPSTREAM_UNAVAILABLE");
  }
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed < 0 || parsed > Number.MAX_SAFE_INTEGER) {
    throw new ApiError("UPSTREAM_UNAVAILABLE");
  }
  return parsed;
}

function requireString(value: unknown, minimumLength: number, maximumLength: number): string {
  if (typeof value !== "string" || value.length < minimumLength || value.length > maximumLength) {
    throw new ApiError("UPSTREAM_UNAVAILABLE");
  }
  return value;
}

function optionalSafeString(value: unknown, maximumLength: number): string | undefined {
  if (value === undefined || value === "") return undefined;
  const text = requireString(value, 1, maximumLength);
  if (/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/.test(text)) {
    throw new ApiError("UPSTREAM_UNAVAILABLE");
  }
  return text;
}

function requireRecord(value: unknown): Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new ApiError("UPSTREAM_UNAVAILABLE");
  }
  return value as Record<string, unknown>;
}

function optionalRecord(value: unknown): Record<string, unknown> | undefined {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : undefined;
}

function sumFinite(values: readonly number[]): number {
  const sum = values.reduce((total, value) => total + value, 0);
  if (!Number.isFinite(sum) || sum < 0 || sum > Number.MAX_SAFE_INTEGER) {
    throw new ApiError("UPSTREAM_UNAVAILABLE");
  }
  return sum;
}

function requireGeometryForNontrivialLeg(
  distanceMeters: number,
  durationSeconds: number,
  points: readonly Coordinate[],
): void {
  if ((distanceMeters > 0 || durationSeconds > 0) && points.length < 2) {
    throw new ApiError("UPSTREAM_UNAVAILABLE");
  }
}

async function mapWithConcurrency<T, R>(
  values: readonly T[],
  concurrency: number,
  mapper: (value: T, index: number) => Promise<R>,
): Promise<R[]> {
  const results: Array<R | undefined> = new Array(values.length);
  let nextIndex = 0;
  const worker = async (): Promise<void> => {
    while (true) {
      const index = nextIndex;
      nextIndex += 1;
      if (index >= values.length) return;
      const value = values[index];
      if (value === undefined) throw new ApiError("BACKEND_UNAVAILABLE");
      results[index] = await mapper(value, index);
    }
  };
  await Promise.all(Array.from({ length: Math.min(concurrency, values.length) }, () => worker()));
  return results.map((result) => {
    if (result === undefined) throw new ApiError("BACKEND_UNAVAILABLE");
    return result;
  });
}

function isSafeCredential(value: string): boolean {
  return typeof value === "string" && value.length > 0 && value.length <= 256 && !/[\u0000-\u001f\u007f]/.test(value);
}

function boundedInteger(value: number, minimum: number, maximum: number, label: string): number {
  if (!Number.isSafeInteger(value) || value < minimum || value > maximum) {
    throw new Error(`${label} is invalid`);
  }
  return value;
}

class AsyncSemaphore {
  private active = 0;
  private readonly waiters: SemaphoreWaiter[] = [];

  constructor(
    private readonly limit: number,
    private readonly maxWaiters: number,
  ) {}

  acquire(timeoutMillis: number): Promise<() => void> {
    if (this.active < this.limit) {
      this.active += 1;
      return Promise.resolve(this.releaseFunction());
    }
    if (this.waiters.length >= this.maxWaiters) {
      return Promise.reject(new ApiError("UPSTREAM_UNAVAILABLE"));
    }
    return new Promise((resolve, reject) => {
      let waiter: SemaphoreWaiter | undefined;
      const timeout = setTimeout(() => {
        if (waiter === undefined) return;
        const index = this.waiters.indexOf(waiter);
        if (index < 0) return;
        this.waiters.splice(index, 1);
        reject(new ApiError("UPSTREAM_UNAVAILABLE"));
      }, timeoutMillis);
      waiter = { resolve, timeout };
      this.waiters.push(waiter);
    });
  }

  private releaseFunction(): () => void {
    let released = false;
    return () => {
      if (released) return;
      released = true;
      const waiter = this.waiters.shift();
      if (waiter === undefined) {
        this.active -= 1;
        return;
      }
      clearTimeout(waiter.timeout);
      waiter.resolve(this.releaseFunction());
    };
  }
}

class SlidingWindowStartLimiter {
  private readonly starts: number[] = [];
  private tail: Promise<void> = Promise.resolve();

  constructor(
    private readonly maximumStarts: number,
    private readonly clock: () => number,
    private readonly sleep: (milliseconds: number) => Promise<void>,
  ) {}

  acquire(): Promise<void> {
    const next = this.tail.then(() => this.waitForSlot());
    this.tail = next.catch(() => undefined);
    return next;
  }

  private async waitForSlot(): Promise<void> {
    while (true) {
      const now = this.clock();
      if (!Number.isFinite(now)) throw new ApiError("BACKEND_UNAVAILABLE");
      while (this.starts.length > 0 && now - (this.starts[0] ?? now) >= RATE_WINDOW_MILLIS) {
        this.starts.shift();
      }
      if (this.starts.length < this.maximumStarts) {
        this.starts.push(now);
        return;
      }
      const oldest = this.starts[0];
      if (oldest === undefined) throw new ApiError("BACKEND_UNAVAILABLE");
      const waitMillis = Math.max(1, Math.ceil(oldest + RATE_WINDOW_MILLIS - now));
      await this.sleep(waitMillis);
    }
  }
}

type SemaphoreWaiter = Readonly<{
  resolve: (release: () => void) => void;
  timeout: ReturnType<typeof setTimeout>;
}>;
