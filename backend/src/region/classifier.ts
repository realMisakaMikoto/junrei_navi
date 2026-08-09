import { createHash } from "node:crypto";
import { closeSync, fstatSync, openSync, readFileSync } from "node:fs";
import type { Coordinate, MapProvider, TerritoryRegion } from "../contract.js";
import { ApiError } from "../errors.js";

const POLYGON_REGIONS = [
  "MAINLAND_CHINA",
  "CHINA_OFFICIAL_MAP_ONLY",
  "HONG_KONG_SAR",
  "MACAO_SAR",
  "CHINA_TAIWAN",
  "JAPAN",
] as const satisfies readonly TerritoryRegion[];

type PolygonRegion = (typeof POLYGON_REGIONS)[number];
type Position = readonly [longitude: number, latitude: number];
type GeoJsonGeometry = Readonly<{
  type: "Polygon" | "MultiPolygon";
  coordinates: unknown;
}>;

export type RegionDataDocument = Readonly<{
  schemaVersion: 1;
  regionDataVersion: string;
  source: Readonly<{ name: string; url: string }>;
  license: Readonly<{ name: string; url: string }>;
  review: Readonly<{
    authority: string;
    approvalId: string;
    reviewedAt: string;
    approved: true;
  }>;
  checksumSha256: string;
  features: ReadonlyArray<Readonly<{ region: PolygonRegion; geometry: GeoJsonGeometry }>>;
}>;

type ChecksumPayload = Omit<RegionDataDocument, "checksumSha256">;

export type RegionDataMetadata = Readonly<{
  version: string;
  sourceName: string;
  licenseName: string;
  reviewAuthority: string;
  reviewApprovalId: string;
  reviewedAt: string;
  checksumSha256: string;
}>;

export type JourneyRegion = Readonly<{
  provider: MapProvider;
  regions: ReadonlySet<TerritoryRegion>;
}>;

export class RegionDataError extends Error {}

export class TerritoryRegionClassifier {
  private constructor(
    readonly metadata: RegionDataMetadata,
    private readonly features: ReadonlyArray<RegionFeature>,
  ) {}

  static load(path: string): TerritoryRegionClassifier {
    let value: unknown;
    let fileDescriptor: number | undefined;
    try {
      fileDescriptor = openSync(path, "r");
      const file = fstatSync(fileDescriptor);
      if (!file.isFile() || file.size > MAX_DOCUMENT_BYTES) {
        throw new RegionDataError("Approved region data exceeds the size limit");
      }
      const bytes = readFileSync(fileDescriptor);
      if (bytes.byteLength > MAX_DOCUMENT_BYTES) {
        throw new RegionDataError("Approved region data exceeds the size limit");
      }
      value = JSON.parse(bytes.toString("utf8"));
    } catch (error) {
      throw new RegionDataError("Approved region data could not be loaded", { cause: error });
    } finally {
      if (fileDescriptor !== undefined) closeSync(fileDescriptor);
    }
    return TerritoryRegionClassifier.fromDocument(value);
  }

  static fromDocument(value: unknown): TerritoryRegionClassifier {
    try {
      const document = parseDocument(value);
      const payload = checksumPayload(document);
      const actualChecksum = computeRegionDataChecksum(payload);
      if (actualChecksum !== document.checksumSha256.toLowerCase()) {
        throw new RegionDataError("Region data checksum does not match");
      }
      const budget: GeometryBudget = { polygons: 0, rings: 0, positions: 0 };
      const features = document.features.map((feature) => new RegionFeature(
        feature.region,
        parseGeometry(feature.geometry, budget),
      ));
      for (const region of POLYGON_REGIONS) {
        if (!features.some((feature) => feature.region === region)) {
          throw new RegionDataError(`Region data is missing ${region}`);
        }
      }
      return new TerritoryRegionClassifier(
        {
          version: document.regionDataVersion,
          sourceName: document.source.name,
          licenseName: document.license.name,
          reviewAuthority: document.review.authority,
          reviewApprovalId: document.review.approvalId,
          reviewedAt: document.review.reviewedAt,
          checksumSha256: document.checksumSha256.toLowerCase(),
        },
        features,
      );
    } catch (error) {
      if (error instanceof RegionDataError) throw error;
      throw new RegionDataError("Approved region data is invalid", { cause: error });
    }
  }

  classify(coordinate: Coordinate): TerritoryRegion | undefined {
    if (!validCoordinate(coordinate)) return undefined;
    const point: Position = [coordinate.longitude, coordinate.latitude];
    const matches = new Set<TerritoryRegion>();
    for (const feature of this.features) {
      const relation = feature.relationTo(point);
      if (relation === "BOUNDARY") return undefined;
      if (relation === "INSIDE") matches.add(feature.region);
    }
    if (matches.size > 1) return undefined;
    return matches.values().next().value ?? "OTHER";
  }

  classifyJourney(coordinates: readonly Coordinate[], mode?: "TRANSIT" | string): JourneyRegion {
    if (coordinates.length === 0) throw new ApiError("REGION_UNRESOLVED");
    const regions = new Set<TerritoryRegion>();
    const providers = new Set<MapProvider>();
    for (const coordinate of coordinates) {
      const region = this.classify(coordinate);
      if (region === undefined) throw new ApiError("REGION_UNRESOLVED");
      regions.add(region);
      providers.add(providerForRegion(region));
    }
    if (providers.size !== 1) throw new ApiError("MIXED_MAP_PROVIDERS");
    if (mode === "TRANSIT" && regions.has("JAPAN") && regions.size > 1) {
      throw new ApiError("MIXED_TRANSIT_REGIONS");
    }
    return { provider: providers.values().next().value as MapProvider, regions };
  }
}

export function providerForRegion(region: TerritoryRegion): MapProvider {
  return region === "MAINLAND_CHINA" || region === "CHINA_OFFICIAL_MAP_ONLY" ? "AMAP" : "GOOGLE";
}

export function computeRegionDataChecksum(payload: unknown): string {
  return createHash("sha256").update(stableJson(payload), "utf8").digest("hex");
}

class RegionFeature {
  constructor(
    readonly region: PolygonRegion,
    private readonly polygons: readonly Polygon[],
  ) {}

  relationTo(point: Position): PointRelation {
    let inside = false;
    for (const polygon of this.polygons) {
      const relation = polygon.relationTo(point);
      if (relation === "BOUNDARY") return relation;
      if (relation === "INSIDE") inside = true;
    }
    return inside ? "INSIDE" : "OUTSIDE";
  }
}

class Polygon {
  private readonly bounds: Bounds;

  constructor(
    private readonly outer: Ring,
    private readonly holes: readonly Ring[],
  ) {
    this.bounds = outer.bounds;
  }

  relationTo(point: Position): PointRelation {
    if (!this.bounds.contains(point)) return "OUTSIDE";
    const outer = this.outer.relationTo(point);
    if (outer !== "INSIDE") return outer;
    for (const hole of this.holes) {
      const relation = hole.relationTo(point);
      if (relation === "BOUNDARY") return relation;
      if (relation === "INSIDE") return "OUTSIDE";
    }
    return "INSIDE";
  }
}

class Ring {
  readonly bounds: Bounds;

  constructor(private readonly positions: readonly Position[]) {
    this.bounds = Bounds.enclosing(positions);
  }

  relationTo(point: Position): PointRelation {
    if (!this.bounds.contains(point)) return "OUTSIDE";
    let inside = false;
    for (let index = 1; index < this.positions.length; index += 1) {
      const from = this.positions[index - 1];
      const to = this.positions[index];
      if (from === undefined || to === undefined) continue;
      if (onSegment(point, from, to)) return "BOUNDARY";
      const crosses = (from[1] > point[1]) !== (to[1] > point[1]);
      if (crosses) {
        const longitude = ((to[0] - from[0]) * (point[1] - from[1])) / (to[1] - from[1]) + from[0];
        if (point[0] < longitude) inside = !inside;
      }
    }
    return inside ? "INSIDE" : "OUTSIDE";
  }
}

class Bounds {
  private constructor(
    private readonly minimumLongitude: number,
    private readonly minimumLatitude: number,
    private readonly maximumLongitude: number,
    private readonly maximumLatitude: number,
  ) {}

  static enclosing(positions: readonly Position[]): Bounds {
    let minimumLongitude = Number.POSITIVE_INFINITY;
    let minimumLatitude = Number.POSITIVE_INFINITY;
    let maximumLongitude = Number.NEGATIVE_INFINITY;
    let maximumLatitude = Number.NEGATIVE_INFINITY;
    for (const position of positions) {
      minimumLongitude = Math.min(minimumLongitude, position[0]);
      minimumLatitude = Math.min(minimumLatitude, position[1]);
      maximumLongitude = Math.max(maximumLongitude, position[0]);
      maximumLatitude = Math.max(maximumLatitude, position[1]);
    }
    return new Bounds(
      minimumLongitude,
      minimumLatitude,
      maximumLongitude,
      maximumLatitude,
    );
  }

  contains(point: Position): boolean {
    return point[0] >= this.minimumLongitude - EPSILON &&
      point[0] <= this.maximumLongitude + EPSILON &&
      point[1] >= this.minimumLatitude - EPSILON &&
      point[1] <= this.maximumLatitude + EPSILON;
  }
}

type PointRelation = "OUTSIDE" | "BOUNDARY" | "INSIDE";

function parseDocument(value: unknown): RegionDataDocument {
  const record = requireRecord(value, "Region data root");
  requireExactKeys(
    record,
    ["schemaVersion", "regionDataVersion", "source", "license", "review", "checksumSha256", "features"],
    "Region data root",
  );
  if (record["schemaVersion"] !== 1) throw new RegionDataError("Unsupported region data schema");
  const regionDataVersion = requireText(record["regionDataVersion"], "regionDataVersion", 64);
  const source = requireMetadataLink(record["source"], "source");
  const license = requireMetadataLink(record["license"], "license");
  const reviewRecord = requireRecord(record["review"], "review");
  requireExactKeys(reviewRecord, ["authority", "approvalId", "reviewedAt", "approved"], "review");
  const review = {
    authority: requireText(reviewRecord["authority"], "review authority", 256),
    approvalId: requireText(reviewRecord["approvalId"], "review approvalId", 256),
    reviewedAt: requireIsoInstant(reviewRecord["reviewedAt"]),
    approved: reviewRecord["approved"],
  };
  if (review.approved !== true) throw new RegionDataError("Region data is not approved");
  const checksumSha256 = requireText(record["checksumSha256"], "checksumSha256", 64);
  if (!/^[a-fA-F0-9]{64}$/.test(checksumSha256)) {
    throw new RegionDataError("Region data checksum is invalid");
  }
  const featureValues = record["features"];
  if (!Array.isArray(featureValues) || featureValues.length === 0 || featureValues.length > 64) {
    throw new RegionDataError("Region data features are invalid");
  }
  const features = featureValues.map((featureValue) => {
    const feature = requireRecord(featureValue, "feature");
    requireExactKeys(feature, ["region", "geometry"], "feature");
    const region = feature["region"];
    if (!POLYGON_REGIONS.includes(region as PolygonRegion)) {
      throw new RegionDataError("Region data contains an unsupported region");
    }
    const geometry = requireRecord(feature["geometry"], "geometry");
    requireExactKeys(geometry, ["type", "coordinates"], "geometry");
    const geometryType = geometry["type"];
    if (geometryType !== "Polygon" && geometryType !== "MultiPolygon") {
      throw new RegionDataError("Region geometry type is invalid");
    }
    return {
      region: region as PolygonRegion,
      geometry: {
        type: geometryType as GeoJsonGeometry["type"],
        coordinates: geometry["coordinates"],
      },
    };
  });
  return {
    schemaVersion: 1,
    regionDataVersion,
    source,
    license,
    review: { ...review, approved: true },
    checksumSha256,
    features,
  };
}

function checksumPayload(document: RegionDataDocument): ChecksumPayload {
  const { checksumSha256: _checksum, ...payload } = document;
  return payload;
}

function requireMetadataLink(value: unknown, name: string): { name: string; url: string } {
  const record = requireRecord(value, name);
  requireExactKeys(record, ["name", "url"], name);
  const url = requireText(record["url"], `${name} url`, 2_048);
  let parsed: URL;
  try {
    parsed = new URL(url);
  } catch {
    throw new RegionDataError(`${name} URL is invalid`);
  }
  const authority = /^https:\/\/([^/?#]+)(?:[/?#]|$)/i.exec(url)?.[1];
  if (
    parsed.protocol !== "https:" ||
    authority === undefined ||
    authority.includes("@") ||
    parsed.hostname.length === 0
  ) {
    throw new RegionDataError(`${name} URL must be an absolute credential-free HTTPS URL`);
  }
  return { name: requireText(record["name"], `${name} name`, 256), url };
}

function requireIsoInstant(value: unknown): string {
  const text = requireText(value, "reviewedAt", 64);
  const milliseconds = Date.parse(text);
  const canonical = text.includes(".") ? text : text.replace("Z", ".000Z");
  if (
    !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3})?Z$/.test(text) ||
    !Number.isFinite(milliseconds) ||
    new Date(milliseconds).toISOString() !== canonical
  ) {
    throw new RegionDataError("reviewedAt must be a UTC instant");
  }
  return text;
}

function parseGeometry(
  geometry: GeoJsonGeometry,
  budget: GeometryBudget,
): readonly Polygon[] {
  const polygons = geometry.type === "Polygon" ? [geometry.coordinates] : geometry.coordinates;
  if (!Array.isArray(polygons) || polygons.length === 0) {
    throw new RegionDataError("Region geometry polygons are invalid");
  }
  budget.polygons += polygons.length;
  if (budget.polygons > MAX_POLYGONS) {
    throw new RegionDataError("Region data contains too many polygons");
  }
  return polygons.map((polygonValue) => {
    if (!Array.isArray(polygonValue) || polygonValue.length === 0) {
      throw new RegionDataError("Region polygon rings are invalid");
    }
    budget.rings += polygonValue.length;
    if (budget.rings > MAX_RINGS) {
      throw new RegionDataError("Region data contains too many rings");
    }
    const rings = polygonValue.map((ring) => parseRing(ring, budget));
    const outer = rings[0];
    if (outer === undefined) throw new RegionDataError("Region polygon has no outer ring");
    return new Polygon(outer, rings.slice(1));
  });
}

function parseRing(value: unknown, budget: GeometryBudget): Ring {
  if (!Array.isArray(value) || value.length < 4 || value.length > MAX_POSITIONS_PER_RING) {
    throw new RegionDataError("Region ring size is invalid");
  }
  budget.positions += value.length;
  if (budget.positions > MAX_TOTAL_POSITIONS) {
    throw new RegionDataError("Region data contains too many positions");
  }
  const positions = value.map((positionValue): Position => {
    if (!Array.isArray(positionValue) || positionValue.length !== 2) {
      throw new RegionDataError("Region position is invalid");
    }
    const [longitude, latitude] = positionValue;
    if (
      typeof longitude !== "number" || !Number.isFinite(longitude) || longitude < -180 || longitude > 180 ||
      typeof latitude !== "number" || !Number.isFinite(latitude) || latitude < -90 || latitude > 90
    ) {
      throw new RegionDataError("Region position is outside WGS84 bounds");
    }
    return [longitude, latitude];
  });
  const first = positions[0];
  const last = positions.at(-1);
  if (first === undefined || last === undefined || first[0] !== last[0] || first[1] !== last[1]) {
    throw new RegionDataError("Region ring is not closed");
  }
  if (new Set(positions.slice(0, -1).map((position) => `${position[0]},${position[1]}`)).size < 3) {
    throw new RegionDataError("Region ring is degenerate");
  }
  if (Math.abs(signedArea(positions)) <= AREA_EPSILON) {
    throw new RegionDataError("Region ring has zero area");
  }
  return new Ring(positions);
}

type GeometryBudget = {
  polygons: number;
  rings: number;
  positions: number;
};

function signedArea(positions: readonly Position[]): number {
  let area = 0;
  for (let index = 1; index < positions.length; index += 1) {
    const from = positions[index - 1];
    const to = positions[index];
    if (from !== undefined && to !== undefined) area += from[0] * to[1] - to[0] * from[1];
  }
  return area / 2;
}

function onSegment(point: Position, from: Position, to: Position): boolean {
  const deltaLongitude = to[0] - from[0];
  const deltaLatitude = to[1] - from[1];
  const cross = (point[0] - from[0]) * deltaLatitude - (point[1] - from[1]) * deltaLongitude;
  const tolerance = EPSILON * (Math.abs(deltaLongitude) + Math.abs(deltaLatitude) + 1);
  return Math.abs(cross) <= tolerance &&
    point[0] >= Math.min(from[0], to[0]) - EPSILON &&
    point[0] <= Math.max(from[0], to[0]) + EPSILON &&
    point[1] >= Math.min(from[1], to[1]) - EPSILON &&
    point[1] <= Math.max(from[1], to[1]) + EPSILON;
}

function requireRecord(value: unknown, name: string): Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new RegionDataError(`${name} must be an object`);
  }
  return value as Record<string, unknown>;
}

function requireExactKeys(
  record: Readonly<Record<string, unknown>>,
  expectedKeys: readonly string[],
  name: string,
): void {
  const actualKeys = Object.keys(record);
  if (
    actualKeys.length !== expectedKeys.length ||
    expectedKeys.some((key) => !Object.hasOwn(record, key))
  ) {
    throw new RegionDataError(`${name} contains unsupported or missing fields`);
  }
}

function requireText(value: unknown, name: string, maximumLength: number): string {
  if (typeof value !== "string" || value.length === 0 || value.length > maximumLength) {
    throw new RegionDataError(`${name} is invalid`);
  }
  return value;
}

function validCoordinate(coordinate: Coordinate): boolean {
  return Number.isFinite(coordinate.latitude) && coordinate.latitude >= -90 && coordinate.latitude <= 90 &&
    Number.isFinite(coordinate.longitude) && coordinate.longitude >= -180 && coordinate.longitude <= 180;
}

function stableJson(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(stableJson).join(",")}]`;
  if (typeof value === "object" && value !== null) {
    const record = value as Record<string, unknown>;
    return `{${Object.keys(record).sort().map((key) => `${JSON.stringify(key)}:${stableJson(record[key])}`).join(",")}}`;
  }
  return JSON.stringify(value);
}

const EPSILON = 1e-10;
const AREA_EPSILON = 1e-14;
const MAX_POLYGONS = 10_000;
const MAX_RINGS = 5_000;
const MAX_POSITIONS_PER_RING = 1_000_000;
const MAX_TOTAL_POSITIONS = 2_000_000;
const MAX_DOCUMENT_BYTES = 64 * 1_024 * 1_024;
