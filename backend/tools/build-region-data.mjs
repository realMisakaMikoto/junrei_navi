import { createHash } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import polygonClipping from "polygon-clipping";
import * as shapefile from "shapefile";

const CHINA_SOURCE_SHA256 = "3af8294f9ad61cc2bf84c1bb7e4bbf86a6336c68d754b699a0e6ddc33ef81486";
const JAPAN_ARCHIVE_SHA256 = "826a2b54630f2d15376bc1831aafe77f72f107e1c39ef0034bb41b184330faaa";
const REGION_DATA_VERSION = "2025.09-cn-gs2024-0650-jp-gm2.1-r1";
const REVIEWED_AT = "2026-08-09T14:52:08Z";
const PROVENANCE_URL =
  "https://github.com/realMisakaMikoto/junrei_navi/blob/main/docs/REGION_DATA_PROVENANCE_v0.2.5.md";

const TAIWAN_GB = "156710000";
const HONG_KONG_GB = "156810000";
const MACAO_GB = "156820000";
const SPECIAL_REGION_POLYGON_SHA256 = new Set([
  "746acfa6c5742bca6a796a5191f9580708f72a2ad1b7e39ae62914ffe695b67c",
  "fa862fc273e6cd5dbb9e6a6ca7c699a8eea9818a2b6ac1f3becfda25dcf48607",
  "43a1323b8fec892ed4834b067759bd181724a4961084287ed6135109454bd305",
]);

const EXPECTED_GB_CODES = [
  "156110000", "156120000", "156130000", "156140000", "156150000",
  "156210000", "156220000", "156230000", "156310000", "156320000",
  "156330000", "156340000", "156350000", "156360000", "156370000",
  "156410000", "156420000", "156430000", "156440000", "156450000",
  "156460000", "156500000", "156510000", "156520000", "156530000",
  "156540000", "156610000", "156620000", "156630000", "156640000",
  "156650000", TAIWAN_GB, HONG_KONG_GB, MACAO_GB,
];

const options = parseOptions(process.argv.slice(2));
const chinaBytes = await readVerified(options.china, CHINA_SOURCE_SHA256, "China source");
await readVerified(options.japanArchive, JAPAN_ARCHIVE_SHA256, "Japan archive");

const china = JSON.parse(chinaBytes.toString("utf8"));
const chinaPolygons = validateChinaSource(china);
const japanCoordinates = await dissolveJapan(options.japanShp);
const features = buildFeatures(chinaPolygons, japanCoordinates);

const payload = {
  schemaVersion: 1,
  regionDataVersion: REGION_DATA_VERSION,
  source: {
    name: "Tianditu Administrative Division 2025-09 + GSI Global Map Japan 2.1",
    url: PROVENANCE_URL,
  },
  license: {
    name: "Tianditu visualization/attribution terms + GSI Public Data License 1.0",
    url: `${PROVENANCE_URL}#license-and-review`,
  },
  review: {
    authority: "Ministry of Natural Resources (CN) / Geospatial Information Authority of Japan",
    approvalId: "GS(2024)0650; GSI-PDL1.0",
    reviewedAt: REVIEWED_AT,
    approved: true,
  },
  features,
};
const checksumSha256 = sha256(Buffer.from(stableJson(payload), "utf8"));
const document = { ...payload, checksumSha256 };
const outputBytes = Buffer.from(`${stableJson(document)}\n`, "utf8");
const output = resolve(options.output);
await mkdir(dirname(output), { recursive: true });
await writeFile(output, outputBytes, { flag: "wx", mode: 0o600 });

const budget = geometryBudget(features);
process.stdout.write(`${JSON.stringify({
  output,
  bytes: outputBytes.byteLength,
  sha256: sha256(outputBytes),
  checksumSha256,
  regionDataVersion: REGION_DATA_VERSION,
  ...budget,
})}\n`);

function parseOptions(arguments_) {
  const values = {};
  for (let index = 0; index < arguments_.length; index += 2) {
    const name = arguments_[index];
    const value = arguments_[index + 1];
    if (!name?.startsWith("--") || value === undefined) usage();
    values[name.slice(2)] = value;
  }
  for (const name of ["china", "japan-archive", "japan-shp", "output"]) {
    if (!values[name]) usage();
  }
  return {
    china: resolve(values.china),
    japanArchive: resolve(values["japan-archive"]),
    japanShp: resolve(values["japan-shp"]),
    output: resolve(values.output),
  };
}

function usage() {
  throw new Error(
    "Usage: node scripts/build-region-data.mjs --china <geojson> --japan-archive <zip> " +
      "--japan-shp <shp> --output <json>",
  );
}

async function readVerified(path, expectedSha256, name) {
  const bytes = await readFile(path);
  if (sha256(bytes) !== expectedSha256) throw new Error(`${name} SHA-256 does not match the reviewed source`);
  return bytes;
}

function validateChinaSource(value) {
  if (value?.type !== "FeatureCollection" || !Array.isArray(value.features)) {
    throw new Error("China source is not a GeoJSON FeatureCollection");
  }
  const polygons = value.features.filter((feature) =>
    feature?.geometry?.type === "Polygon" || feature?.geometry?.type === "MultiPolygon",
  );
  const codes = polygons.map((feature) => feature?.properties?.gb).sort();
  if (JSON.stringify(codes) !== JSON.stringify([...EXPECTED_GB_CODES].sort())) {
    throw new Error("China source province codes do not match the reviewed set");
  }
  if (value.features.length !== 42 || value.features.length - polygons.length !== 8) {
    throw new Error("China source feature composition does not match the reviewed file");
  }
  return polygons.sort((left, right) => left.properties.gb.localeCompare(right.properties.gb));
}

async function dissolveJapan(shpPath) {
  const source = await shapefile.open(shpPath);
  const geometries = [];
  while (true) {
    const result = await source.read();
    if (result.done) break;
    const geometry = result.value?.geometry;
    if (geometry?.type === "Polygon") geometries.push([geometry.coordinates]);
    else if (geometry?.type === "MultiPolygon") geometries.push(geometry.coordinates);
    else throw new Error("Japan source contains a non-polygon geometry");
  }
  if (geometries.length !== 2_914) throw new Error("Japan source feature count does not match version 2.1");

  let dissolved = [];
  for (let index = 0; index < geometries.length; index += 100) {
    const batch = polygonClipping.union(...geometries.slice(index, index + 100));
    dissolved = dissolved.length === 0 ? batch : polygonClipping.union(dissolved, batch);
  }
  if (dissolved.length !== 1_110) throw new Error("Japan dissolved polygon count does not match the reviewed result");
  return dissolved;
}

function buildFeatures(chinaFeatures, japanCoordinates) {
  const features = [];
  for (const feature of chinaFeatures) {
    const gb = feature.properties.gb;
    if (gb === TAIWAN_GB || gb === HONG_KONG_GB || gb === MACAO_GB) continue;
    features.push({ region: "MAINLAND_CHINA", geometry: normalizedGeometry(feature.geometry) });
  }

  const taiwan = chinaFeatures.find((feature) => feature.properties.gb === TAIWAN_GB);
  const taiwanPolygons = normalizedGeometry(taiwan.geometry).coordinates;
  const officialMapOnly = [];
  const remainingTaiwan = [];
  for (const polygon of taiwanPolygons) {
    const digest = sha256(Buffer.from(JSON.stringify(polygon), "utf8"));
    (SPECIAL_REGION_POLYGON_SHA256.has(digest) ? officialMapOnly : remainingTaiwan).push(polygon);
  }
  const selectedHashes = new Set(officialMapOnly.map((polygon) =>
    sha256(Buffer.from(JSON.stringify(polygon), "utf8")),
  ));
  if (selectedHashes.size !== SPECIAL_REGION_POLYGON_SHA256.size) {
    throw new Error("Official-map-only polygon selection does not match the reviewed components");
  }

  features.push({
    region: "CHINA_OFFICIAL_MAP_ONLY",
    geometry: { type: "MultiPolygon", coordinates: officialMapOnly },
  });
  features.push({
    region: "HONG_KONG_SAR",
    geometry: normalizedGeometry(chinaFeatures.find((feature) => feature.properties.gb === HONG_KONG_GB).geometry),
  });
  features.push({
    region: "MACAO_SAR",
    geometry: normalizedGeometry(chinaFeatures.find((feature) => feature.properties.gb === MACAO_GB).geometry),
  });
  features.push({
    region: "CHINA_TAIWAN",
    geometry: { type: "MultiPolygon", coordinates: remainingTaiwan },
  });
  features.push({
    region: "JAPAN",
    geometry: { type: "MultiPolygon", coordinates: japanCoordinates },
  });
  return features;
}

function normalizedGeometry(geometry) {
  if (geometry.type === "Polygon") return { type: "MultiPolygon", coordinates: [geometry.coordinates] };
  if (geometry.type === "MultiPolygon") return { type: "MultiPolygon", coordinates: geometry.coordinates };
  throw new Error("Expected polygon geometry");
}

function geometryBudget(features) {
  let polygons = 0;
  let rings = 0;
  let positions = 0;
  for (const feature of features) {
    const values = feature.geometry.type === "Polygon"
      ? [feature.geometry.coordinates]
      : feature.geometry.coordinates;
    polygons += values.length;
    for (const polygon of values) {
      rings += polygon.length;
      for (const ring of polygon) positions += ring.length;
    }
  }
  return { features: features.length, polygons, rings, positions };
}

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

function stableJson(value) {
  if (Array.isArray(value)) return `[${value.map(stableJson).join(",")}]`;
  if (value !== null && typeof value === "object") {
    return `{${Object.keys(value).sort().map((key) =>
      `${JSON.stringify(key)}:${stableJson(value[key])}`,
    ).join(",")}}`;
  }
  return JSON.stringify(value);
}
