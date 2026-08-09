export type Coordinate = Readonly<{
  latitude: number;
  longitude: number;
}>;

export type TerritoryRegion =
  | "MAINLAND_CHINA"
  | "CHINA_OFFICIAL_MAP_ONLY"
  | "HONG_KONG_SAR"
  | "MACAO_SAR"
  | "CHINA_TAIWAN"
  | "JAPAN"
  | "OTHER";

export type MapProvider = "GOOGLE" | "AMAP";
export type CoordinateSystem = "WGS84" | "GCJ02";

export type RoadMode = "DRIVE" | "BICYCLE" | "WALK";
export type TravelMode = RoadMode | "TRANSIT";
export type RouteObjective = "FASTEST" | "SHORTEST";
export type TransitRoutingPreference = "LESS_WALKING" | "FEWER_TRANSFERS";
export type TransitTravelMode = "BUS" | "SUBWAY" | "TRAIN" | "LIGHT_RAIL" | "RAIL";

export type MatrixRequest = Readonly<{
  mode: RoadMode;
  coordinates: Coordinate[];
  departureTime?: string;
  objective: RouteObjective;
}>;

type TransitTime =
  | Readonly<{ departureTime: string; arrivalTime?: never }>
  | Readonly<{ departureTime?: never; arrivalTime: string }>
  | Readonly<{ departureTime?: never; arrivalTime?: never }>;

export type RouteRequest =
  | Readonly<{
      mode: RoadMode;
      locations: Coordinate[];
    }>
  | (Readonly<{
      mode: "TRANSIT";
      locations: Coordinate[];
      transitRoutingPreference?: TransitRoutingPreference;
      transitTravelModes?: TransitTravelMode[];
    }> & TransitTime);

export type NavigationReservationRequest = Readonly<{
  destinationCount: number;
}>;

export type V2NavigationReservationRequest = Readonly<{
  origin: Coordinate;
  destinations: Coordinate[];
}>;

export type ProviderMetadata = Readonly<{
  provider: MapProvider;
  coordinateSystem: CoordinateSystem;
  regionDataVersion: string;
}>;

export type V2MatrixResponse = NormalizedMatrix & ProviderMetadata;
export type V2RouteResponse = NormalizedRoute & ProviderMetadata;

export type V2NavigationReservation = ProviderMetadata &
  Readonly<{
    reservedDestinations: number;
    executionStrategy: "GOOGLE_NAVIGATION_SDK" | "EXTERNAL_AMAP_MAINLAND";
  }>;

export type V2Policy = Readonly<{
  apiVersion: "2";
  regionDataVersion: string;
  minimumAppVersion: string;
  v1SunsetAt: string;
  providers: Readonly<{
    google: "enabled" | "disabled";
    amap: "enabled" | "disabled";
  }>;
}>;

export type NormalizedMatrixElement = Readonly<{
  originIndex: number;
  destinationIndex: number;
  status: "OK" | "UNREACHABLE";
  distanceMeters?: number;
  durationSeconds?: number;
}>;

export type NormalizedMatrix = Readonly<{
  elements: NormalizedMatrixElement[];
}>;

export type NormalizedTransitDetails = Readonly<{
  departureStop?: string;
  arrivalStop?: string;
  departureTime?: string;
  arrivalTime?: string;
  departureTimeZone?: string;
  arrivalTimeZone?: string;
  lineName?: string;
  lineShortName?: string;
  headsign?: string;
  vehicleName?: string;
  vehicleType?: string;
  stopCount?: number;
}>;

export type NormalizedRouteStep = Readonly<{
  travelMode: TravelMode;
  distanceMeters: number;
  durationSeconds: number;
  encodedPolyline?: string;
  instruction?: string;
  maneuver?: string;
  transit?: NormalizedTransitDetails;
}>;

export type NormalizedRouteLeg = Readonly<{
  distanceMeters: number;
  durationSeconds: number;
  encodedPolyline?: string;
  steps: NormalizedRouteStep[];
}>;

export type NormalizedRoute = Readonly<{
  distanceMeters: number;
  durationSeconds: number;
  encodedPolyline?: string;
  legs: NormalizedRouteLeg[];
}>;
