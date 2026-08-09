import type { TerritoryRegion } from "../src/contract.js";
import {
  computeRegionDataChecksum,
  TerritoryRegionClassifier,
  type RegionDataDocument,
} from "../src/region/classifier.js";

type PolygonRegion = Exclude<TerritoryRegion, "OTHER">;

export function syntheticRegionDocument(
  featureOverrides?: ReadonlyArray<Readonly<{ region: PolygonRegion; geometry: RegionDataDocument["features"][number]["geometry"] }>>,
): RegionDataDocument {
  const features = featureOverrides ?? [
    square("MAINLAND_CHINA", -60),
    square("CHINA_OFFICIAL_MAP_ONLY", -40),
    square("HONG_KONG_SAR", -20),
    square("MACAO_SAR", 0),
    square("CHINA_TAIWAN", 20),
    square("JAPAN", 40),
  ];
  const payload = {
    schemaVersion: 1 as const,
    regionDataVersion: "synthetic-v1",
    source: { name: "Synthetic test geometry", url: "https://example.invalid/source" },
    license: { name: "Synthetic test license", url: "https://example.invalid/license" },
    review: {
      authority: "Synthetic test authority",
      approvalId: "synthetic-approval",
      reviewedAt: "2026-01-01T00:00:00Z",
      approved: true as const,
    },
    features,
  };
  return { ...payload, checksumSha256: computeRegionDataChecksum(payload) };
}

export function syntheticRegionClassifier(): TerritoryRegionClassifier {
  return TerritoryRegionClassifier.fromDocument(syntheticRegionDocument());
}

export function square(region: PolygonRegion, minimumLongitude: number) {
  return {
    region,
    geometry: {
      type: "Polygon" as const,
      coordinates: [[
        [minimumLongitude, 10],
        [minimumLongitude + 10, 10],
        [minimumLongitude + 10, 20],
        [minimumLongitude, 20],
        [minimumLongitude, 10],
      ]],
    },
  };
}
