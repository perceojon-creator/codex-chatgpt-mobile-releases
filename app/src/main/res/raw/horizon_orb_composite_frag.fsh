#version 300 es

precision highp float;
precision highp sampler2D;

in vec2 sdfPosition;
out vec4 fragColor;

uniform float uOrbScale;
uniform sampler2D uMaterialTexture;

const float MIN_HORIZON_ORB_SCALE = 0.75;

void main() {
  float orbScale = max(clamp(uOrbScale, 0.0, 1.0), MIN_HORIZON_ORB_SCALE);
  vec2 scaledSdfPosition = sdfPosition / orbScale;
  float sdfDistance = length(scaledSdfPosition) - 1.0;
  float edgeWidth = max(fwidth(sdfDistance), 0.000001);
  if (sdfDistance >= edgeWidth * 2.0) {
    fragColor = vec4(0.0);
    return;
  }

  vec2 materialUv = scaledSdfPosition * 0.5 + 0.5;
  vec4 materialColor = texture(uMaterialTexture, materialUv);
  float clampedShape = 1.0 - smoothstep(-edgeWidth, edgeWidth, sdfDistance + (edgeWidth * 0.5));
  float alpha = materialColor.a * clampedShape;
  fragColor = vec4(materialColor.rgb * alpha, alpha);
}
