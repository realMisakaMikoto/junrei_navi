import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { test } from "node:test";
import { computeRegionDataChecksum, RegionDataError, TerritoryRegionClassifier } from "../src/region/classifier.js";
import { square, syntheticRegionDocument } from "./region-fixture.js";

test("approved versioned region data classifies every provider region and outside as OTHER", () => {
  const classifier = TerritoryRegionClassifier.fromDocument(syntheticRegionDocument());
  assert.equal(classifier.metadata.version, "synthetic-v1");
  assert.equal(classifier.classify({ latitude: 15, longitude: -55 }), "MAINLAND_CHINA");
  assert.equal(classifier.classify({ latitude: 15, longitude: -35 }), "CHINA_OFFICIAL_MAP_ONLY");
  assert.equal(classifier.classify({ latitude: 15, longitude: -15 }), "HONG_KONG_SAR");
  assert.equal(classifier.classify({ latitude: 15, longitude: 5 }), "MACAO_SAR");
  assert.equal(classifier.classify({ latitude: 15, longitude: 25 }), "CHINA_TAIWAN");
  assert.equal(classifier.classify({ latitude: 15, longitude: 45 }), "JAPAN");
  assert.equal(classifier.classify({ latitude: -20, longitude: 100 }), "OTHER");
});

test("shared Android/backend vectors fix canonical number checksums and classifications", () => {
  const fixtureDirectory = join(
    dirname(fileURLToPath(import.meta.url)),
    "..",
    "..",
    "app",
    "src",
    "test",
    "resources",
    "region",
  );
  const numberVector = JSON.parse(readFileSync(
    join(fixtureDirectory, "canonical_numbers_TEST_ONLY.json"),
    "utf8",
  )) as { payload: unknown; sha256: string };
  assert.equal(numberVector.sha256, "a4c7e0b7a83aa3375ba2dc817448ab3c4c81ca4b6cf161a7c8a64e9d53af2729");
  assert.equal(computeRegionDataChecksum(numberVector.payload), numberVector.sha256);

  const document = JSON.parse(readFileSync(
    join(fixtureDirectory, "territory_regions_v1_TEST_ONLY.json"),
    "utf8",
  )) as Record<string, unknown>;
  const checksum = document["checksumSha256"];
  const { checksumSha256: _checksum, ...payload } = document;
  assert.equal(checksum, "6064f6807cf334739553285108ae4b7247b8079253aa27a75b0ca816cfbf8873");
  assert.equal(computeRegionDataChecksum(payload), checksum);
  const classifier = TerritoryRegionClassifier.fromDocument(document);
  for (const [longitude, expected] of [
    [100.5, "MAINLAND_CHINA"],
    [102.5, "CHINA_OFFICIAL_MAP_ONLY"],
    [104.5, "HONG_KONG_SAR"],
    [106.5, "MACAO_SAR"],
    [108.5, "CHINA_TAIWAN"],
    [110.5, "JAPAN"],
  ] as const) {
    assert.equal(classifier.classify({ latitude: 20.5, longitude }), expected);
  }
  assert.equal(classifier.classify({ latitude: 20.5, longitude: 100 }), undefined);
  assert.equal(classifier.classify({ latitude: 0, longitude: 0 }), "OTHER");
});

test("polygon boundaries, hole boundaries, and overlapping regions fail closed", () => {
  const features = [
    square("MAINLAND_CHINA", -60),
    square("CHINA_OFFICIAL_MAP_ONLY", -40),
    square("HONG_KONG_SAR", -20),
    square("MACAO_SAR", 0),
    square("CHINA_TAIWAN", 20),
    {
      region: "JAPAN" as const,
      geometry: {
        type: "Polygon" as const,
        coordinates: [
          [[40, 10], [50, 10], [50, 20], [40, 20], [40, 10]],
          [[43, 13], [47, 13], [47, 17], [43, 17], [43, 13]],
        ],
      },
    },
    {
      region: "HONG_KONG_SAR" as const,
      geometry: {
        type: "Polygon" as const,
        coordinates: [[[41, 14], [44, 14], [44, 18], [41, 18], [41, 14]]],
      },
    },
  ];
  const classifier = TerritoryRegionClassifier.fromDocument(syntheticRegionDocument(features));
  assert.equal(classifier.classify({ latitude: 15, longitude: 40 }), undefined);
  assert.equal(classifier.classify({ latitude: 13, longitude: 45 }), undefined);
  assert.equal(classifier.classify({ latitude: 15, longitude: 45 }), "OTHER");
  assert.equal(classifier.classify({ latitude: 15, longitude: 42 }), undefined);
});

test("journey classification rejects mixed providers, unresolved points, and mixed Japan transit", () => {
  const classifier = TerritoryRegionClassifier.fromDocument(syntheticRegionDocument());
  assert.throws(
    () => classifier.classifyJourney([
      { latitude: 15, longitude: -55 },
      { latitude: 15, longitude: 45 },
    ]),
    hasCode("MIXED_MAP_PROVIDERS"),
  );
  assert.throws(
    () => classifier.classifyJourney([{ latitude: 15, longitude: -60 }]),
    hasCode("REGION_UNRESOLVED"),
  );
  assert.throws(
    () => classifier.classifyJourney([
      { latitude: 15, longitude: 45 },
      { latitude: -20, longitude: 100 },
    ], "TRANSIT"),
    hasCode("MIXED_TRANSIT_REGIONS"),
  );
});

test("missing approval metadata and checksum tampering are rejected", () => {
  const document = syntheticRegionDocument();
  assert.throws(
    () => TerritoryRegionClassifier.fromDocument({
      ...document,
      review: { ...document.review, approved: false },
    }),
    RegionDataError,
  );
  const invalidReviewPayload = {
    schemaVersion: document.schemaVersion,
    regionDataVersion: document.regionDataVersion,
    source: document.source,
    license: document.license,
    review: { ...document.review, reviewedAt: "2026-02-30T00:00:00Z" },
    features: document.features,
  };
  assert.throws(
    () => TerritoryRegionClassifier.fromDocument({
      ...invalidReviewPayload,
      checksumSha256: computeRegionDataChecksum(invalidReviewPayload),
    }),
    /UTC instant/,
  );
  const tampered = {
    ...document,
    regionDataVersion: "tampered",
  };
  assert.notEqual(computeRegionDataChecksum({
    schemaVersion: tampered.schemaVersion,
    regionDataVersion: tampered.regionDataVersion,
    source: tampered.source,
    license: tampered.license,
    review: tampered.review,
    features: tampered.features,
  }), document.checksumSha256);
  assert.throws(() => TerritoryRegionClassifier.fromDocument(tampered), RegionDataError);
});

test("fixed region schema objects reject unknown fields", () => {
  const document = syntheticRegionDocument();
  const firstFeature = document.features[0];
  assert.ok(firstFeature);
  const variants: unknown[] = [
    { ...document, unexpected: "TEST_ONLY" },
    { ...document, source: { ...document.source, unexpected: "TEST_ONLY" } },
    { ...document, license: { ...document.license, unexpected: "TEST_ONLY" } },
    { ...document, review: { ...document.review, unexpected: "TEST_ONLY" } },
    {
      ...document,
      features: [{ ...firstFeature, unexpected: "TEST_ONLY" }, ...document.features.slice(1)],
    },
    {
      ...document,
      features: [
        {
          ...firstFeature,
          geometry: { ...firstFeature.geometry, unexpected: "TEST_ONLY" },
        },
        ...document.features.slice(1),
      ],
    },
  ];

  for (const variant of variants) {
    assert.throws(
      () => TerritoryRegionClassifier.fromDocument(variant),
      /unsupported or missing fields/,
    );
  }
});

test("metadata URLs require hierarchical credential-free HTTPS", () => {
  const document = syntheticRegionDocument();
  const { checksumSha256: _checksumSha256, ...basePayload } = document;
  for (const url of [
    "http://example.invalid/source",
    "https:opaque",
    "https:///missing-authority",
    "https://user:secret@example.invalid/source",
    "https://@example.invalid/source",
  ]) {
    const payload = {
      ...basePayload,
      source: { ...basePayload.source, url },
    };
    assert.throws(
      () => TerritoryRegionClassifier.fromDocument({
        ...payload,
        checksumSha256: computeRegionDataChecksum(payload),
      }),
      /credential-free HTTPS URL/,
    );
  }
  void _checksumSha256;
});

test("moderately large rings are bounded iteratively without argument-limit failure", () => {
  const positions: number[][] = [];
  for (let index = 0; index < 20_000; index += 1) {
    const angle = (index / 20_000) * Math.PI * 2;
    positions.push([40 + Math.cos(angle) * 5, 15 + Math.sin(angle) * 5]);
  }
  positions.push([...positions[0] as number[]]);
  const features = [
    square("MAINLAND_CHINA", -60),
    square("CHINA_OFFICIAL_MAP_ONLY", -40),
    square("HONG_KONG_SAR", -20),
    square("MACAO_SAR", 0),
    square("CHINA_TAIWAN", 20),
    {
      region: "JAPAN" as const,
      geometry: { type: "Polygon" as const, coordinates: [positions] },
    },
  ];
  const classifier = TerritoryRegionClassifier.fromDocument(syntheticRegionDocument(features));
  assert.equal(classifier.classify({ latitude: 15, longitude: 40 }), "JAPAN");
});

test("polygon and ring limits are aggregate across the entire region document", () => {
  const regions = [
    ["MAINLAND_CHINA", -60],
    ["CHINA_OFFICIAL_MAP_ONLY", -40],
    ["HONG_KONG_SAR", -20],
    ["MACAO_SAR", 0],
    ["CHINA_TAIWAN", 20],
    ["JAPAN", 40],
  ] as const;
  const tooManyRings = regions.map(([region, minimumLongitude]) => {
    const ring = [
      [minimumLongitude, 10],
      [minimumLongitude + 10, 10],
      [minimumLongitude + 10, 20],
      [minimumLongitude, 20],
      [minimumLongitude, 10],
    ];
    return {
      region,
      geometry: {
        type: "Polygon" as const,
        coordinates: Array.from({ length: 834 }, () => ring),
      },
    };
  });
  assert.throws(
    () => TerritoryRegionClassifier.fromDocument(syntheticRegionDocument(tooManyRings)),
    /too many rings/,
  );

  const tooManyPolygons = [
    square("MAINLAND_CHINA", -60),
    square("CHINA_OFFICIAL_MAP_ONLY", -40),
    square("HONG_KONG_SAR", -20),
    square("MACAO_SAR", 0),
    square("CHINA_TAIWAN", 20),
    {
      region: "JAPAN" as const,
      geometry: {
        type: "MultiPolygon" as const,
        coordinates: Array.from({ length: 9_996 }, () => []),
      },
    },
  ];
  assert.throws(
    () => TerritoryRegionClassifier.fromDocument(syntheticRegionDocument(tooManyPolygons)),
    /too many polygons/,
  );
});

function hasCode(code: string): (error: unknown) => boolean {
  return (error) => typeof error === "object" && error !== null && "code" in error && error.code === code;
}
