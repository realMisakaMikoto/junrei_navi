import type {
  Coordinate,
  MatrixRequest,
  NormalizedMatrix,
  NormalizedRoute,
  RouteRequest,
  V2MatrixResponse,
  V2NavigationReservation,
  V2NavigationReservationRequest,
  V2RouteResponse,
} from "../contract.js";
import { ApiError } from "../errors.js";
import type { RoutesProvider } from "../google/routes.js";
import type { QuotaLedger, UpstreamReservation, UpstreamUsageLedger } from "../quota/ledger.js";
import { providerForRegion, TerritoryRegionClassifier } from "../region/classifier.js";

export interface AmapRoutesProvider extends RoutesProvider {
  usageForMatrix(request: MatrixRequest): readonly UpstreamReservation[];
  usageForRoute(request: RouteRequest): readonly UpstreamReservation[];
}

export type V2RoutingDependencies = Readonly<{
  regions: TerritoryRegionClassifier;
  google: RoutesProvider;
  amap?: AmapRoutesProvider;
  googleQuota: QuotaLedger;
  upstreamQuota: UpstreamUsageLedger;
}>;

export class V2RoutingService {
  constructor(private readonly dependencies: V2RoutingDependencies) {}

  get regionDataVersion(): string {
    return this.dependencies.regions.metadata.version;
  }

  get amapEnabled(): boolean {
    const health = this.dependencies.upstreamQuota.amapHealth();
    return this.dependencies.amap !== undefined && health.healthy && health.configured && health.billingEnabled;
  }

  providerFor(coordinates: readonly Coordinate[], mode?: string): "GOOGLE" | "AMAP" {
    return this.dependencies.regions.classifyJourney(coordinates, mode).provider;
  }

  matrixProviderAvailable(provider: "GOOGLE" | "AMAP"): boolean {
    return provider === "GOOGLE" || this.amapEnabled;
  }

  async matrix(request: MatrixRequest): Promise<V2MatrixResponse> {
    const selection = this.dependencies.regions.classifyJourney(request.coordinates, request.mode);
    if (selection.provider === "GOOGLE") {
      this.dependencies.googleQuota.reserve({
        bucket: "matrix",
        units: request.coordinates.length ** 2,
      });
      return this.withMetadata(await this.dependencies.google.matrix(request), "GOOGLE", "WGS84");
    }
    const amap = this.requireAmap();
    this.dependencies.upstreamQuota.reserveAmap(amap.usageForMatrix(request));
    return this.withMetadata(await amap.matrix(request), "AMAP", "GCJ02");
  }

  async route(request: RouteRequest): Promise<V2RouteResponse> {
    const selection = this.dependencies.regions.classifyJourney(request.locations, request.mode);
    if (selection.provider === "GOOGLE") {
      this.dependencies.googleQuota.reserve({ bucket: "route", units: 1 });
      return this.withMetadata(await this.dependencies.google.route(request), "GOOGLE", "WGS84");
    }
    if (
      request.mode === "TRANSIT" &&
      (request.arrivalTime !== undefined || request.transitTravelModes !== undefined)
    ) {
      throw new ApiError("INVALID_ARGUMENT");
    }
    const amap = this.requireAmap();
    this.dependencies.upstreamQuota.reserveAmap(amap.usageForRoute(request));
    return this.withMetadata(await amap.route(request), "AMAP", "GCJ02");
  }

  reserveNavigation(request: V2NavigationReservationRequest): V2NavigationReservation {
    const coordinates = [request.origin, ...request.destinations];
    const selection = this.dependencies.regions.classifyJourney(coordinates);
    if (selection.provider === "GOOGLE") {
      this.dependencies.googleQuota.reserve({
        bucket: "navigation",
        units: request.destinations.length,
      });
      return {
        reservedDestinations: request.destinations.length,
        executionStrategy: "GOOGLE_NAVIGATION_SDK",
        provider: "GOOGLE",
        coordinateSystem: "WGS84",
        regionDataVersion: this.regionDataVersion,
      };
    }
    if (!this.amapEnabled) throw new ApiError("BACKEND_UNAVAILABLE");
    return {
      reservedDestinations: request.destinations.length,
      executionStrategy: "EXTERNAL_AMAP_MAINLAND",
      provider: "AMAP",
      coordinateSystem: "WGS84",
      regionDataVersion: this.regionDataVersion,
    };
  }

  assertV1GoogleCoordinates(coordinates: readonly Coordinate[]): void {
    for (const coordinate of coordinates) {
      const region = this.dependencies.regions.classify(coordinate);
      if (region === undefined) throw new ApiError("REGION_UNRESOLVED");
      if (providerForRegion(region) === "AMAP") throw new ApiError("CLIENT_UPGRADE_REQUIRED");
    }
  }

  private requireAmap(): AmapRoutesProvider {
    if (!this.amapEnabled || this.dependencies.amap === undefined) {
      throw new ApiError("BACKEND_UNAVAILABLE");
    }
    return this.dependencies.amap;
  }

  private withMetadata<T extends NormalizedMatrix | NormalizedRoute>(
    response: T,
    provider: "GOOGLE" | "AMAP",
    coordinateSystem: "WGS84" | "GCJ02",
  ): T & { provider: "GOOGLE" | "AMAP"; coordinateSystem: "WGS84" | "GCJ02"; regionDataVersion: string } {
    return {
      ...response,
      provider,
      coordinateSystem,
      regionDataVersion: this.regionDataVersion,
    };
  }
}
