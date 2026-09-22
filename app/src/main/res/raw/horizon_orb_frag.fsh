#version 300 es
// Production shader based on openai/openai-android#22210
// Donor head: bd2c548ff092a2ecdd499b48047fd8fe34f165a7
// Only edit values inside the EDITABLE HORIZON MATERIAL block below.
// OCIO and time-of-day palette state are intentionally absent.

precision highp float;
precision highp int;
precision highp sampler2D;

in vec3 generatedCoordinate;
in vec2 sdfPosition;
out vec4 fragColor;

uniform float uWaveFrame;
uniform float uAuthoredFrame;
uniform float uWaveAmplitude;
uniform float uTextureFlowFrame;
uniform float uTextureEdgeWarp;
uniform float uListeningTextureNoiseScale;
uniform vec2 uSpeakingWatercolorOffset0;
uniform vec2 uSpeakingWatercolorOffset1;
uniform vec2 uSpeakingWatercolorOffset2;
uniform uint uPaletteIndex;
uniform sampler2D uWatercolor;
#ifdef HORIZON_BAKED_FIELDS
uniform sampler2D uBakedWaveNoise;
uniform sampler2D uBakedVoronoiPosition;
#endif

// BEGIN EDITABLE HORIZON MATERIAL
// Raw normalized shader-space RGB. These are not display-sRGB hex values.
struct HorizonPalette {
  vec4 shadowColor;
  vec4 midLowColor;
  vec4 midHighColor;
  vec4 highlightColor;
};

// Palette indexes: 0 default, 1 blue, 2 green, 3 yellow, 4 pink, 5 orange, 6 purple, 7 black.
const HorizonPalette materialDefaultPalette = HorizonPalette(
  vec4(0.48958295583724976, 0.5062166452407837, 1.0, 1.0),
  vec4(0.5075743198394775, 0.6806396245956421, 1.0, 1.0),
  vec4(1.0, 1.0, 1.0, 1.0),
  vec4(0.8022463321685791, 0.8336243629455566, 1.0, 1.0)
);
const HorizonPalette materialGreenPalette = HorizonPalette(
  vec4(0.0, 0.627581179, 0.131065607, 1.0),
  vec4(0.482586682, 0.819607854, 0.646261632, 1.0),
  vec4(1.0, 1.0, 1.0, 1.0),
  vec4(0.760784388, 0.917647064, 0.807843208, 1.0)
);
const HorizonPalette materialYellowPalette = HorizonPalette(
  vec4(1.0, 0.615798414, 0.0, 1.0),
  vec4(1.0, 0.896790802, 0.285905391, 1.0),
  vec4(1.0, 1.0, 1.0, 1.0),
  vec4(0.992156863, 0.894745171, 0.617015302, 1.0)
);
const HorizonPalette materialPinkPalette = HorizonPalette(
  vec4(0.941176474, 0.466666669, 0.686274529, 1.0),
  vec4(0.984313726, 0.749019623, 0.843137264, 1.0),
  vec4(1.0, 1.0, 1.0, 1.0),
  vec4(0.988235295, 0.896634519, 0.934801519, 1.0)
);
const HorizonPalette materialOrangePalette = HorizonPalette(
  vec4(0.933333337, 0.42786175, 0.12191844, 1.0),
  vec4(1.0, 0.727038801, 0.307055056, 1.0),
  vec4(1.0, 1.0, 1.0, 1.0),
  vec4(1.0, 0.913937926, 0.745554626, 1.0)
);
const HorizonPalette materialPurplePalette = HorizonPalette(
  vec4(0.53725493, 0.321568638, 0.933333337, 1.0),
  vec4(0.663499951, 0.613026738, 1.0, 1.0),
  vec4(1.0, 1.0, 1.0, 1.0),
  vec4(0.920169115, 0.883867264, 0.988235295, 1.0)
);
const HorizonPalette materialBlackPalette = HorizonPalette(
  vec4(0.070326567, 0.07515198, 0.080666736, 1.0),
  vec4(0.595656097, 0.608763099, 0.623742521, 1.0),
  vec4(1.0, 1.0, 1.0, 1.0),
  vec4(0.854716659, 0.872834802, 0.893541336, 1.0)
);

HorizonPalette materialPaletteForIndex(uint paletteIndex) {
  // Mali r38 rejects different cases returning the same const HorizonPalette; shared palettes must use one arm.
  // Legacy GLSL compilers reject unsigned case labels, so keep both the selector and labels signed.
  switch (int(paletteIndex)) {
    case 2:
      return materialGreenPalette;
    case 3:
      return materialYellowPalette;
    case 4:
      return materialPinkPalette;
    case 5:
      return materialOrangePalette;
    case 6:
      return materialPurplePalette;
    case 7:
      return materialBlackPalette;
    // Default and Blue share the lavender default palette.
    default:
      return materialDefaultPalette;
  }
}

// Each ramp smoothly transitions between its start and end values.
const float materialMidLowRampStart = 0.3522728383541107;
const float materialMidLowRampEnd = 0.7400000095367432;
const float materialMidHighRampStart = 0.4113636016845703;
const float materialMidHighRampEnd = 0.5586364269256592;
const float materialHighlightRampStart = 0.3431819975376129;
const float materialHighlightRampEnd = 0.6200000047683716;
// END EDITABLE HORIZON MATERIAL

#ifndef HORIZON_BAKED_FIELDS
uint rotateBits(uint value, uint shift) {
  return (value << shift) | (value >> (32u - shift));
}
void hashMix(inout uint a, inout uint b, inout uint c) {
  a -= c;
  a ^= rotateBits(c, 4u);
  c += b;
  b -= a;
  b ^= rotateBits(a, 6u);
  a += c;
  c -= b;
  c ^= rotateBits(b, 8u);
  b += a;
  a -= c;
  a ^= rotateBits(c, 16u);
  c += b;
  b -= a;
  b ^= rotateBits(a, 19u);
  a += c;
  c -= b;
  c ^= rotateBits(b, 4u);
  b += a;
}

void hashFinal(inout uint a, inout uint b, inout uint c) {
  c ^= b;
  c -= rotateBits(b, 14u);
  a ^= c;
  a -= rotateBits(c, 11u);
  b ^= a;
  b -= rotateBits(a, 25u);
  c ^= b;
  c -= rotateBits(b, 16u);
  a ^= c;
  a -= rotateBits(c, 4u);
  b ^= a;
  b -= rotateBits(a, 14u);
  c ^= b;
  c -= rotateBits(b, 24u);
}

uint hashUint2(uint x, uint y) {
  uint a = 0xdeadbeefu + (2u << 2u) + 13u;
  uint b = a;
  uint c = a;
  b += y;
  a += x;
  hashFinal(a, b, c);
  return c;
}

uint hashUint4(uint x, uint y, uint z, uint w) {
  uint a = 0xdeadbeefu + (4u << 2u) + 13u;
  uint b = a;
  uint c = a;
  a += x;
  b += y;
  c += z;
  hashMix(a, b, c);
  a += w;
  hashFinal(a, b, c);
  return c;
}

uint hashInt4(ivec4 value) {
  return hashUint4(uint(value.x), uint(value.y), uint(value.z), uint(value.w));
}

float hashUint2ToFloat(uint x, uint y) {
  return float(hashUint2(x, y)) / float(0xffffffffu);
}

float hashVec2ToFloat(vec2 value) {
  return hashUint2ToFloat(floatBitsToUint(value.x), floatBitsToUint(value.y));
}

vec4 randomVec4Offset(float seed) {
  return vec4(
    100.0 + hashVec2ToFloat(vec2(seed, 0.0)) * 100.0,
    100.0 + hashVec2ToFloat(vec2(seed, 1.0)) * 100.0,
    100.0 + hashVec2ToFloat(vec2(seed, 2.0)) * 100.0,
    100.0 + hashVec2ToFloat(vec2(seed, 3.0)) * 100.0
  );
}

float fadeNoise(float value) {
  return value * value * value * (value * (value * 6.0 - 15.0) + 10.0);
}

float signedComponent(float value, uint flag) {
  return flag != 0u ? -value : value;
}

float noiseGradient(uint hash, vec4 value) {
  uint h = hash & 31u;
  float u = h < 24u ? value.x : value.y;
  float v = h < 16u ? value.y : value.z;
  float s = h < 8u ? value.z : value.w;
  return signedComponent(u, h & 1u) + signedComponent(v, h & 2u) + signedComponent(s, h & 4u);
}

float triMix(
  float v0,
  float v1,
  float v2,
  float v3,
  float v4,
  float v5,
  float v6,
  float v7,
  vec3 factor
) {
  float x1 = 1.0 - factor.x;
  float y1 = 1.0 - factor.y;
  float z1 = 1.0 - factor.z;
  return z1 * (y1 * (v0 * x1 + v1 * factor.x) + factor.y * (v2 * x1 + v3 * factor.x)) +
    factor.z * (y1 * (v4 * x1 + v5 * factor.x) + factor.y * (v6 * x1 + v7 * factor.x));
}

float quadMix(
  float v0,
  float v1,
  float v2,
  float v3,
  float v4,
  float v5,
  float v6,
  float v7,
  float v8,
  float v9,
  float v10,
  float v11,
  float v12,
  float v13,
  float v14,
  float v15,
  vec4 factor
) {
  return mix(
    triMix(v0, v1, v2, v3, v4, v5, v6, v7, factor.xyz),
    triMix(v8, v9, v10, v11, v12, v13, v14, v15, factor.xyz),
    factor.w
  );
}

float perlin4(vec4 value) {
  ivec4 cell = ivec4(floor(value));
  vec4 fraction = value - floor(value);
  vec4 curve = vec4(
    fadeNoise(fraction.x),
    fadeNoise(fraction.y),
    fadeNoise(fraction.z),
    fadeNoise(fraction.w)
  );

  return quadMix(
    noiseGradient(hashInt4(cell + ivec4(0, 0, 0, 0)), fraction - vec4(0.0, 0.0, 0.0, 0.0)),
    noiseGradient(hashInt4(cell + ivec4(1, 0, 0, 0)), fraction - vec4(1.0, 0.0, 0.0, 0.0)),
    noiseGradient(hashInt4(cell + ivec4(0, 1, 0, 0)), fraction - vec4(0.0, 1.0, 0.0, 0.0)),
    noiseGradient(hashInt4(cell + ivec4(1, 1, 0, 0)), fraction - vec4(1.0, 1.0, 0.0, 0.0)),
    noiseGradient(hashInt4(cell + ivec4(0, 0, 1, 0)), fraction - vec4(0.0, 0.0, 1.0, 0.0)),
    noiseGradient(hashInt4(cell + ivec4(1, 0, 1, 0)), fraction - vec4(1.0, 0.0, 1.0, 0.0)),
    noiseGradient(hashInt4(cell + ivec4(0, 1, 1, 0)), fraction - vec4(0.0, 1.0, 1.0, 0.0)),
    noiseGradient(hashInt4(cell + ivec4(1, 1, 1, 0)), fraction - vec4(1.0, 1.0, 1.0, 0.0)),
    noiseGradient(hashInt4(cell + ivec4(0, 0, 0, 1)), fraction - vec4(0.0, 0.0, 0.0, 1.0)),
    noiseGradient(hashInt4(cell + ivec4(1, 0, 0, 1)), fraction - vec4(1.0, 0.0, 0.0, 1.0)),
    noiseGradient(hashInt4(cell + ivec4(0, 1, 0, 1)), fraction - vec4(0.0, 1.0, 0.0, 1.0)),
    noiseGradient(hashInt4(cell + ivec4(1, 1, 0, 1)), fraction - vec4(1.0, 1.0, 0.0, 1.0)),
    noiseGradient(hashInt4(cell + ivec4(0, 0, 1, 1)), fraction - vec4(0.0, 0.0, 1.0, 1.0)),
    noiseGradient(hashInt4(cell + ivec4(1, 0, 1, 1)), fraction - vec4(1.0, 0.0, 1.0, 1.0)),
    noiseGradient(hashInt4(cell + ivec4(0, 1, 1, 1)), fraction - vec4(0.0, 1.0, 1.0, 1.0)),
    noiseGradient(hashInt4(cell + ivec4(1, 1, 1, 1)), fraction - vec4(1.0, 1.0, 1.0, 1.0)),
    curve
  );
}

vec4 noiseRepeat4(vec4 value) {
  vec4 precisionCorrection = 0.5 * step(vec4(1000000.0), abs(value));
  return value - vec4(100000.0) * trunc(value / vec4(100000.0)) + precisionCorrection;
}

float signedNoise4(vec4 value) {
  return 0.8344 * perlin4(noiseRepeat4(value));
}

// Adapted from Blender 5.0's GPU 4D F1 Voronoi and fractal Voronoi shader paths.
ivec4 voronoiHashPcg4d(ivec4 signedValue) {
  uvec4 value = uvec4(signedValue);
  value = value * 1664525u + uvec4(1013904223u);
  value.x += value.y * value.w;
  value.y += value.z * value.x;
  value.z += value.x * value.y;
  value.w += value.y * value.z;
  value ^= uvec4(ivec4(value) >> 16);
  value.x += value.y * value.w;
  value.y += value.z * value.x;
  value.z += value.x * value.y;
  value.w += value.y * value.z;
  return ivec4(value);
}

vec4 voronoiHashInt4ToVec4(ivec4 value) {
  return vec4(voronoiHashPcg4d(value) & ivec4(0x7fffffff)) * (1.0 / float(0x7fffffff));
}
#endif

vec3 rotateX(vec3 value, float angle) {
  float c = cos(angle);
  float s = sin(angle);
  return vec3(value.x, c * value.y - s * value.z, s * value.y + c * value.z);
}

vec3 rotateY(vec3 value, float angle) {
  float c = cos(angle);
  float s = sin(angle);
  return vec3(c * value.x + s * value.z, value.y, -s * value.x + c * value.z);
}

vec3 rotateZ(vec3 value, float angle) {
  float c = cos(angle);
  float s = sin(angle);
  return vec3(c * value.x - s * value.y, s * value.x + c * value.y, value.z);
}

vec3 rotateEulerXYZ(vec3 value, vec3 rotation) {
  vec3 transformed = rotateX(value, rotation.x);
  transformed = rotateY(transformed, rotation.y);
  transformed = rotateZ(transformed, rotation.z);
  return transformed;
}

vec3 mappingPoint(vec3 value, vec3 location, vec3 rotation, vec3 scale) {
  return rotateEulerXYZ(value * scale, rotation) + location;
}

#ifndef HORIZON_BAKED_FIELDS
float noiseFbm4Detail2Normalized(vec4 value, float roughness) {
  float frequency = 1.0;
  float amplitude = 1.0;
  float maxAmplitude = 0.0;
  float sum = 0.0;

  sum += signedNoise4(frequency * value) * amplitude;
  maxAmplitude += amplitude;
  amplitude *= roughness;
  frequency *= 2.0;

  sum += signedNoise4(frequency * value) * amplitude;
  maxAmplitude += amplitude;
  return 0.5 * sum / max(maxAmplitude, 0.000001) + 0.5;
}

vec3 noiseColor4Detail2Normalized(vec3 coordinate, float w, float scale, float roughness) {
  vec4 point = vec4(coordinate, w) * scale;
  return vec3(
    noiseFbm4Detail2Normalized(point, roughness),
    noiseFbm4Detail2Normalized(point + randomVec4Offset(4.0), roughness),
    noiseFbm4Detail2Normalized(point + randomVec4Offset(5.0), roughness)
  );
}

vec4 voronoiF1Position4Squared(vec4 coordinate, float randomness) {
  vec4 cellPositionFloat = floor(coordinate);
  vec4 localPosition = coordinate - cellPositionFloat;
  ivec4 cellPosition = ivec4(cellPositionFloat);

  float minDistanceSquared = 3.402823466e+38;
  vec4 targetPosition = vec4(0.0);
  for (int u = -1; u <= 1; u++) {
    for (int k = -1; k <= 1; k++) {
      for (int j = -1; j <= 1; j++) {
        for (int i = -1; i <= 1; i++) {
          ivec4 cellOffset = ivec4(i, j, k, u);
          vec4 pointPosition = vec4(cellOffset)
            + voronoiHashInt4ToVec4(cellPosition + cellOffset) * randomness;
          vec4 delta = pointPosition - localPosition;
          float distanceSquared = dot(delta, delta);
          if (distanceSquared < minDistanceSquared) {
            minDistanceSquared = distanceSquared;
            targetPosition = pointPosition;
          }
        }
      }
    }
  }

  return targetPosition + cellPositionFloat;
}

vec3 fractalVoronoiF1Position4Specialized(vec3 coordinate, float w) {
  vec4 point = vec4(coordinate, w) * 8.0;
  vec4 position = vec4(0.0);
  float amplitude = 1.0;
  float octaveScale = 1.0;

  vec4 octavePosition = voronoiF1Position4Squared(point * octaveScale, 0.7185189723968506);
  position = mix(position, octavePosition / octaveScale, amplitude);
  octaveScale *= 2.0;
  amplitude *= 0.5;

  octavePosition = voronoiF1Position4Squared(point * octaveScale, 0.7185189723968506);
  position = mix(position, octavePosition / octaveScale, amplitude);
  octaveScale *= 2.0;
  amplitude *= 0.5;

  octavePosition = voronoiF1Position4Squared(point * octaveScale, 0.7185189723968506);
  position = mix(position, mix(position, octavePosition / octaveScale, amplitude), 0.7999999523162842);
  return position.xyz / 8.0;
}
#else
const float BAKED_FIELD_SLICE_COUNT = 32.0;
const vec2 BAKED_FIELD_ATLAS_GRID = vec2(8.0, 4.0);

vec2 bakedFieldAtlasUv(sampler2D atlas, vec2 uv, float sliceIndex) {
  vec2 tile = vec2(mod(sliceIndex, BAKED_FIELD_ATLAS_GRID.x), floor(sliceIndex / BAKED_FIELD_ATLAS_GRID.x));
  vec2 tileSize = vec2(textureSize(atlas, 0)) / BAKED_FIELD_ATLAS_GRID;
  vec2 innerUv = mix(
    vec2(0.5) / tileSize,
    vec2(1.0) - vec2(0.5) / tileSize,
    clamp(uv, 0.0, 1.0)
  );
  return (tile + innerUv) / BAKED_FIELD_ATLAS_GRID;
}

vec3 sampleBakedField(
  sampler2D atlas,
  vec2 coordinate,
  vec2 domainMin,
  vec2 domainMax,
  float time,
  float timeStep
) {
  vec2 uv = (coordinate - domainMin) / (domainMax - domainMin);
  float phase = mod(max(time, 0.0) / timeStep, BAKED_FIELD_SLICE_COUNT);
  float firstSlice = floor(phase);
  float secondSlice = mod(firstSlice + 1.0, BAKED_FIELD_SLICE_COUNT);
  return mix(
    texture(atlas, bakedFieldAtlasUv(atlas, uv, firstSlice)).rgb,
    texture(atlas, bakedFieldAtlasUv(atlas, uv, secondSlice)).rgb,
    fract(phase)
  );
}

vec3 decodeBakedVoronoiPosition(vec3 encoded) {
  return encoded * 3.5 - vec3(1.25);
}

float cheapHash2(vec2 value) {
  return fract(sin(dot(value, vec2(127.1, 311.7))) * 43758.5453123);
}

float cheapValueNoise2(vec2 value) {
  vec2 cell = floor(value);
  vec2 fraction = fract(value);
  fraction = fraction * fraction * (3.0 - 2.0 * fraction);
  return mix(
    mix(cheapHash2(cell), cheapHash2(cell + vec2(1.0, 0.0)), fraction.x),
    mix(cheapHash2(cell + vec2(0.0, 1.0)), cheapHash2(cell + vec2(1.0, 1.0)), fraction.x),
    fraction.y
  );
}

vec3 cheapAnimatedNoise2(vec2 coordinate, float time, float scale) {
  vec2 point = coordinate * scale + vec2(time * 0.31, -time * 0.23);
  return vec3(
    cheapValueNoise2(point),
    cheapValueNoise2(point + vec2(17.0, 43.0)),
    cheapValueNoise2(point + vec2(59.0, 11.0))
  );
}
#endif

vec4 ramp_color_ramp_006(float fac) {
  if (fac <= materialHighlightRampStart) {
    return vec4(1.0, 1.0, 1.0, 1.0);
  }
  if (fac <= materialHighlightRampEnd) {
    float t = clamp((fac - materialHighlightRampStart) / max(materialHighlightRampEnd - materialHighlightRampStart, 0.000001), 0.0, 1.0);
    t = t * t * (3.0 - 2.0 * t);
    return mix(vec4(1.0, 1.0, 1.0, 1.0), vec4(0.0, 0.0, 0.0, 1.0), t);
  }
  return vec4(0.0, 0.0, 0.0, 1.0);
}

vec4 ramp_color_ramp_005(float fac) {
  if (fac <= materialMidHighRampStart) {
    return vec4(1.0, 1.0, 1.0, 1.0);
  }
  if (fac <= materialMidHighRampEnd) {
    float t = clamp((fac - materialMidHighRampStart) / max(materialMidHighRampEnd - materialMidHighRampStart, 0.000001), 0.0, 1.0);
    t = t * t * (3.0 - 2.0 * t);
    return mix(vec4(1.0, 1.0, 1.0, 1.0), vec4(0.0, 0.0, 0.0, 1.0), t);
  }
  return vec4(0.0, 0.0, 0.0, 1.0);
}

vec4 ramp_color_ramp_009(float fac) {
  if (fac <= materialMidLowRampStart) {
    return vec4(1.0, 1.0, 1.0, 1.0);
  }
  if (fac <= materialMidLowRampEnd) {
    float t = clamp((fac - materialMidLowRampStart) / max(materialMidLowRampEnd - materialMidLowRampStart, 0.000001), 0.0, 1.0);
    t = t * t * (3.0 - 2.0 * t);
    return mix(vec4(1.0, 1.0, 1.0, 1.0), vec4(0.0, 0.0, 0.0, 1.0), t);
  }
  return vec4(0.0, 0.0, 0.0, 1.0);
}

const float MATERIAL_EDGE_MARGIN = 0.08;

vec3 sampleWaveNoise(vec3 position, float frame) {
#ifdef HORIZON_BAKED_FIELDS
  return sampleBakedField(uBakedWaveNoise, position.xy, vec2(0.0, -0.14), vec2(1.0, 0.86), frame, 0.25);
#else
  return noiseColor4Detail2Normalized(position, frame, 1.0, 0.4000000059604645);
#endif
}

vec3 sampleTextureNoise(vec3 position, float frame) {
#ifdef HORIZON_BAKED_FIELDS
  return cheapAnimatedNoise2(position.xy, frame, 12.0);
#else
  return noiseColor4Detail2Normalized(position, frame, 4000.0, 0.5);
#endif
}

vec3 sampleVoronoiPosition(vec3 position, float frame) {
#ifdef HORIZON_BAKED_FIELDS
  return decodeBakedVoronoiPosition(
    sampleBakedField(uBakedVoronoiPosition, position.xy, vec2(-1.0), vec2(2.0), frame, 0.125)
  );
#else
  return fractalVoronoiF1Position4Specialized(position, frame);
#endif
}

void main() {

  HorizonPalette materialPalette = materialPaletteForIndex(uPaletteIndex);
  vec4 materialShadowColor = materialPalette.shadowColor;
  vec4 materialMidLowColor = materialPalette.midLowColor;
  vec4 materialMidHighColor = materialPalette.midHighColor;
  vec4 materialHighlightColor = materialPalette.highlightColor;

  float sdfDistance = length(sdfPosition) - 1.0;
  // Keep a small material margin so linear sampling in the full-resolution composite
  // does not darken the antialiased silhouette edge.
  if (sdfDistance >= MATERIAL_EDGE_MARGIN) {
    fragColor = vec4(0.0);
    return;
  }
  vec3 scaledGenerated = generatedCoordinate;
  float frame = uAuthoredFrame;
  float waveFrame = uWaveFrame;
  float textureFlowFrame = uTextureFlowFrame;
  float authoredFrameBlend120 = clamp(0.5 - 0.5*cos(frame * 2.0 * 3.14159265 / 120.0), 0.0, 1.0);
  vec3 n_texture_coordinate_001_generated = scaledGenerated;
vec3 n_mapping_003_vector = mappingPoint(n_texture_coordinate_001_generated, vec3(0.0, -0.14000000059604645, 0.0), vec3(0.0, 0.0, 0.0), vec3(1.0, 1.0, 1.0));
  vec2 textureEdgeCentered = scaledGenerated.xy - vec2(0.5);
  float textureEdgeRadius = length(textureEdgeCentered);
  float textureEdgeWeight = smoothstep(0.015, 0.44, textureEdgeRadius);
  vec2 textureEdgeDirection = textureEdgeRadius > 0.000001
    ? textureEdgeCentered / textureEdgeRadius
    : vec2(0.0);
  n_mapping_003_vector.xy += textureEdgeDirection * textureEdgeWeight * uTextureEdgeWarp;
float n_value_001_value = waveFrame / 100.0;
vec3 n_noise_texture_009_color = sampleWaveNoise(n_mapping_003_vector, n_value_001_value);
vec3 n_vector_math_015_vector = n_noise_texture_009_color - vec3(0.5, 0.5, 0.5);
float n_value_003_value = (0.800000011920929) * uWaveAmplitude;
vec3 n_vector_math_016_vector = n_vector_math_015_vector * n_value_003_value;
vec3 n_vector_math_020_vector = n_mapping_003_vector + n_vector_math_016_vector;
vec3 n_noise_texture_color = sampleTextureNoise(n_vector_math_020_vector, textureFlowFrame / 10.0);
vec3 n_vector_math_026_vector = (n_noise_texture_color - vec3(0.5, 0.5, 0.5)) * uListeningTextureNoiseScale;
vec3 n_vector_math_028_vector = n_vector_math_026_vector * 0.05999999865889549;
float n_value_value = 0.800000011920929;
vec3 n_vector_math_004_vector = n_vector_math_020_vector * n_value_value;
vec3 n_vector_math_010_vector = n_vector_math_028_vector + n_vector_math_004_vector;
  n_vector_math_010_vector.xy += uSpeakingWatercolorOffset0;
vec4 n_image_texture_004_color = textureGrad(uWatercolor, (n_vector_math_010_vector).xy, dFdx((n_vector_math_010_vector).xy) * (1.0 / 1.5), dFdy((n_vector_math_010_vector).xy) * (1.0 / 1.5));
float n_math_002_value = (n_image_texture_004_color).r - 0.5;
vec3 n_vector_math_005_vector = n_vector_math_004_vector;
vec3 n_vector_math_013_vector = n_vector_math_028_vector + n_vector_math_005_vector;
float n_math_004_value = 1.0 - (n_vector_math_013_vector).y;
vec3 n_combine_xyz_005_vector = vec3((n_vector_math_013_vector).x, n_math_004_value, 0.0);
  n_combine_xyz_005_vector.xy += uSpeakingWatercolorOffset0;
vec4 n_image_texture_005_color = textureGrad(uWatercolor, (n_combine_xyz_005_vector).xy, dFdx((n_combine_xyz_005_vector).xy) * (1.0 / 1.5), dFdy((n_combine_xyz_005_vector).xy) * (1.0 / 1.5));
float n_math_005_value = (n_image_texture_005_color).g - 0.5;
float n_mix_012_result_float = mix(n_math_002_value, n_math_005_value, authoredFrameBlend120);
float n_value_002_value = 0.2 - 0.06*cos((frame - 1.0) * 2.0 * 3.14159265 / 120.0);
float n_math_003_value = n_mix_012_result_float * n_value_002_value;
vec3 n_combine_xyz_004_vector = vec3(n_math_003_value, n_math_003_value, 0.0);
float n_value_009_value = frame / 240.0;
vec3 n_voronoi_texture_position = sampleVoronoiPosition(n_vector_math_020_vector, n_value_009_value);
vec3 n_vector_math_022_vector = n_voronoi_texture_position - vec3(0.5, 0.5, 0.5);
vec3 n_vector_math_023_vector = n_vector_math_022_vector * 0.25999999046325684;
vec3 n_vector_math_vector = n_combine_xyz_004_vector + n_vector_math_023_vector;
vec3 n_vector_math_001_vector = n_vector_math_020_vector + n_vector_math_vector;
vec3 n_mapping_006_vector = mappingPoint(n_vector_math_001_vector, vec3(0.0, 0.559999942779541, 0.0), vec3(0.0, 0.7853981852531433, 0.0), vec3(1.0, 1.0, 1.0));
vec4 n_color_ramp_006_color = ramp_color_ramp_006(((n_mapping_006_vector).x + (n_mapping_006_vector).y + (n_mapping_006_vector).z) / 3.0);
float n_value_005_value = 0.800000011920929;
vec3 n_mapping_vector = mappingPoint(n_vector_math_020_vector, vec3(0.03999999910593033, 0.019999999552965164, 0.0), vec3(0.0, 0.0, 0.0), vec3(n_value_005_value));
vec3 n_vector_math_021_vector = n_vector_math_028_vector + n_mapping_vector;
  n_vector_math_021_vector.xy += uSpeakingWatercolorOffset1;
vec4 n_image_texture_006_color = textureGrad(uWatercolor, (n_vector_math_021_vector).xy, dFdx((n_vector_math_021_vector).xy) * (1.0 / 1.5), dFdy((n_vector_math_021_vector).xy) * (1.0 / 1.5));
float n_math_006_value = (n_image_texture_006_color).r - 0.5;
vec3 n_mapping_001_vector = mappingPoint(n_vector_math_020_vector, vec3(-0.03999999910593033, -0.019999999552965164, 0.0), vec3(0.0, 0.0, 0.0), vec3(n_value_005_value));
vec3 n_vector_math_024_vector = n_vector_math_028_vector + n_mapping_001_vector;
float n_math_008_value = 1.0 - (n_vector_math_024_vector).y;
vec3 n_combine_xyz_007_vector = vec3((n_vector_math_024_vector).x, n_math_008_value, 0.0);
  n_combine_xyz_007_vector.xy += uSpeakingWatercolorOffset1;
vec4 n_image_texture_007_color = textureGrad(uWatercolor, (n_combine_xyz_007_vector).xy, dFdx((n_combine_xyz_007_vector).xy) * (1.0 / 1.5), dFdy((n_combine_xyz_007_vector).xy) * (1.0 / 1.5));
float n_math_009_value = (n_image_texture_007_color).g - 0.5;
float n_mix_013_result_float = mix(n_math_006_value, n_math_009_value, authoredFrameBlend120);
float n_value_004_value = 0.2 - 0.06*cos((frame - 1.0) * 2.0 * 3.14159265 / 80.0);
float n_math_007_value = n_mix_013_result_float * n_value_004_value;
vec3 n_combine_xyz_006_vector = vec3(n_math_007_value, n_math_007_value, 0.0);
vec3 n_vector_math_003_vector = n_combine_xyz_006_vector + n_vector_math_023_vector;
vec3 n_vector_math_002_vector = n_vector_math_020_vector + n_vector_math_003_vector;
vec3 n_mapping_004_vector = mappingPoint(n_vector_math_002_vector, vec3(0.0, 0.25999999046325684, 0.0), vec3(0.0, 0.7853981852531433, 0.0), vec3(1.0, 1.0, 1.0));
vec4 n_color_ramp_005_color = ramp_color_ramp_005(((n_mapping_004_vector).x + (n_mapping_004_vector).y + (n_mapping_004_vector).z) / 3.0);
vec3 n_texture_coordinate_002_generated = scaledGenerated;
vec3 n_mapping_007_vector = mappingPoint(n_texture_coordinate_002_generated, vec3(-0.3199999928474426, 0.0, 0.0), vec3(0.0, 0.0, -1.5707963705062866), vec3(1.0, 1.0, 1.0));
float n_gradient_texture_clamped = clamp((n_mapping_007_vector).x, 0.0, 1.0);
float n_gradient_texture_fac = n_gradient_texture_clamped * n_gradient_texture_clamped * (3.0 - 2.0 * n_gradient_texture_clamped);
float n_value_007_value = 0.800000011920929;
vec3 n_mapping_002_vector = mappingPoint(n_vector_math_020_vector, vec3(-0.07999999821186066, -0.03999999910593033, 0.0), vec3(0.0, 0.0, 0.0), vec3(n_value_007_value));
vec3 n_vector_math_027_vector = n_vector_math_028_vector + n_mapping_002_vector;
  n_vector_math_027_vector.xy += uSpeakingWatercolorOffset2;
vec4 n_image_texture_008_color = textureGrad(uWatercolor, (n_vector_math_027_vector).xy, dFdx((n_vector_math_027_vector).xy) * (1.0 / 1.5), dFdy((n_vector_math_027_vector).xy) * (1.0 / 1.5));
vec4 n_mix_002_result_color = mix(n_image_texture_008_color, vec4(1.0, 1.0, 1.0, 1.0), clamp(((vec4(vec3(n_gradient_texture_fac), 1.0)).r + (vec4(vec3(n_gradient_texture_fac), 1.0)).g + (vec4(vec3(n_gradient_texture_fac), 1.0)).b) / 3.0, 0.0, 1.0));
float n_math_010_value = (n_mix_002_result_color).r - 0.5;
vec3 n_mapping_005_vector = mappingPoint(n_vector_math_020_vector, vec3(-0.9599999189376831, 0.05999999865889549, 0.0), vec3(0.0, 0.0, 0.0), vec3(n_value_007_value));
vec3 n_vector_math_030_vector = n_vector_math_028_vector + n_mapping_005_vector;
float n_math_012_value = 1.0 - (n_vector_math_030_vector).y;
vec3 n_combine_xyz_009_vector = vec3((n_vector_math_030_vector).x, n_math_012_value, 0.0);
  n_combine_xyz_009_vector.xy += uSpeakingWatercolorOffset2;
vec4 n_image_texture_009_color = textureGrad(uWatercolor, (n_combine_xyz_009_vector).xy, dFdx((n_combine_xyz_009_vector).xy) * (1.0 / 1.5), dFdy((n_combine_xyz_009_vector).xy) * (1.0 / 1.5));
vec4 n_mix_001_result_color = mix(n_image_texture_009_color, vec4(1.0, 1.0, 1.0, 1.0), clamp(((vec4(vec3(n_gradient_texture_fac), 1.0)).r + (vec4(vec3(n_gradient_texture_fac), 1.0)).g + (vec4(vec3(n_gradient_texture_fac), 1.0)).b) / 3.0, 0.0, 1.0));
float n_math_013_value = (n_mix_001_result_color).g - 0.5;
float n_mix_014_result_float = mix(n_math_010_value, n_math_013_value, authoredFrameBlend120);
float n_value_006_value = 0.14 - 0.06*cos((frame - 1.0) * 2.0 * 3.14159265 / 100.0);
float n_math_011_value = n_mix_014_result_float * n_value_006_value;
vec3 n_combine_xyz_008_vector = vec3(n_math_011_value, n_math_011_value, 0.0);
vec3 n_vector_math_007_vector = n_combine_xyz_008_vector + n_vector_math_023_vector;
vec3 n_vector_math_006_vector = n_vector_math_020_vector + n_vector_math_007_vector;
vec3 n_mapping_009_vector = mappingPoint(n_vector_math_006_vector, vec3(0.0, 0.12000000476837158, 0.0), vec3(0.0, 0.7853981852531433, 0.0), vec3(1.0, 1.0, 1.0));
vec4 n_color_ramp_009_color = ramp_color_ramp_009(((n_mapping_009_vector).x + (n_mapping_009_vector).y + (n_mapping_009_vector).z) / 3.0);
vec4 n_mix_021_result_color = materialShadowColor;
vec4 n_mix_022_result_color = mix(n_mix_021_result_color, materialMidLowColor, clamp(((n_color_ramp_009_color).r + (n_color_ramp_009_color).g + (n_color_ramp_009_color).b) / 3.0, 0.0, 1.0));
vec4 n_mix_019_result_color = mix(n_mix_022_result_color, materialMidHighColor, clamp(((n_color_ramp_005_color).r + (n_color_ramp_005_color).g + (n_color_ramp_005_color).b) / 3.0, 0.0, 1.0));
vec4 n_mix_020_result_color = mix(n_mix_019_result_color, materialHighlightColor, clamp(((n_color_ramp_006_color).r + (n_color_ramp_006_color).g + (n_color_ramp_006_color).b) / 3.0, 0.0, 1.0));
  fragColor = vec4((n_mix_020_result_color).rgb, 1.0);

}
