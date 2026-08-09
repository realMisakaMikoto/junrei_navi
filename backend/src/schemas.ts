const coordinateSchema = {
  type: "object",
  additionalProperties: false,
  required: ["latitude", "longitude"],
  properties: {
    latitude: { type: "number", minimum: -90, maximum: 90 },
    longitude: { type: "number", minimum: -180, maximum: 180 },
  },
} as const;

export const matrixBodySchema = {
  type: "object",
  additionalProperties: false,
  required: ["mode", "coordinates", "objective"],
  properties: {
    mode: { type: "string", enum: ["DRIVE", "BICYCLE", "WALK"] },
    coordinates: {
      type: "array",
      minItems: 2,
      maxItems: 10,
      items: coordinateSchema,
    },
    departureTime: { type: "string", format: "date-time" },
    objective: { type: "string", enum: ["FASTEST", "SHORTEST"] },
  },
} as const;

export const routeBodySchema = {
  type: "object",
  additionalProperties: false,
  required: ["mode", "locations"],
  properties: {
    mode: { type: "string", enum: ["DRIVE", "BICYCLE", "WALK", "TRANSIT"] },
    locations: {
      type: "array",
      minItems: 2,
      maxItems: 12,
      items: coordinateSchema,
    },
    departureTime: { type: "string", format: "date-time" },
    arrivalTime: { type: "string", format: "date-time" },
    transitRoutingPreference: {
      type: "string",
      enum: ["LESS_WALKING", "FEWER_TRANSFERS"],
    },
    transitTravelModes: {
      type: "array",
      minItems: 1,
      maxItems: 5,
      uniqueItems: true,
      items: {
        type: "string",
        enum: ["BUS", "SUBWAY", "TRAIN", "LIGHT_RAIL", "RAIL"],
      },
    },
  },
  allOf: [
    { not: { required: ["departureTime", "arrivalTime"] } },
    {
      if: {
        properties: { mode: { const: "TRANSIT" } },
        required: ["mode"],
      },
      else: {
        not: {
          anyOf: [
            { required: ["departureTime"] },
            { required: ["arrivalTime"] },
            { required: ["transitRoutingPreference"] },
            { required: ["transitTravelModes"] },
          ],
        },
      },
    },
  ],
} as const;

export const navigationReservationBodySchema = {
  type: "object",
  additionalProperties: false,
  required: ["destinationCount"],
  properties: {
    destinationCount: { type: "integer", minimum: 1, maximum: 25 },
  },
} as const;

export const v2NavigationReservationBodySchema = {
  type: "object",
  additionalProperties: false,
  required: ["origin", "destinations"],
  properties: {
    origin: coordinateSchema,
    destinations: {
      type: "array",
      minItems: 1,
      maxItems: 25,
      items: coordinateSchema,
    },
  },
} as const;
