#!/usr/bin/env bash
set -euo pipefail

mapping_dir="${1:-app/build/outputs/mapping/release}"
class_name="com.google.android.gms.maps.internal.CreatorImpl"
mapping_file="${2:-$mapping_dir/mapping.txt}"
seeds_file="$mapping_dir/seeds.txt"
usage_file="$mapping_dir/usage.txt"
configuration_file="$mapping_dir/configuration.txt"

for required_file in "$mapping_file" "$seeds_file" "$usage_file" "$configuration_file"; do
  if [[ ! -s "$required_file" ]]; then
    echo "Missing release R8 output: $required_file" >&2
    exit 1
  fi
done

contains_exact_line() {
  local expected="$1"
  local file="$2"
  awk -v expected="$expected" '
    { sub(/\r$/, "") }
    $0 == expected { found = 1; exit }
    END { exit(found ? 0 : 1) }
  ' "$file"
}

mapping_header="$class_name -> $class_name:"
if ! grep -Fqx "$mapping_header" "$mapping_file"; then
  echo "Navigation map creator class name was not retained by R8" >&2
  exit 1
fi

mapping_block="$({
  awk -v header="$mapping_header" '
    $0 == header { inside = 1; next }
    inside && $0 !~ /^[[:space:]#]/ { exit }
    inside { print }
  ' "$mapping_file"
} || true)"
if ! grep -Eq 'void <init>\(\).* -> <init>$' <<<"$mapping_block"; then
  echo "Navigation map creator zero-argument constructor is absent after R8" >&2
  exit 1
fi

if ! grep -Fq "$class_name: CreatorImpl()" "$seeds_file"; then
  echo "Navigation map creator constructor is not an R8 seed" >&2
  exit 1
fi

removed_constructor="$({
  awk -v header="$class_name:" '
    $0 == header { inside = 1; next }
    inside && $0 !~ /^[[:space:]]/ { inside = 0 }
    inside && $0 ~ /^[[:space:]]+public void <init>\(\)$/ { print }
  ' "$usage_file"
} || true)"
if [[ -n "$removed_constructor" ]]; then
  echo "Navigation map creator constructor was removed by R8" >&2
  exit 1
fi

shader_program_classes=(
  'com.google.android.libraries.geo.mapcore.internal.legacy.internal.vector.gl.ClientLineLiteShaderState$ClientInjectedStrokeLiteShader'
  'com.google.android.libraries.geo.mapcore.internal.legacy.internal.vector.gl.ClientLineStampShaderState$ClientInjectedStrokeShader'
  'com.google.android.libraries.geo.mapcore.internal.legacy.internal.vector.gl.RoadStrokePointSpriteShaderState$StrokeShaderProgram'
  'com.google.android.libraries.geo.mapcore.internal.legacy.internal.vector.gl.RoadStrokeShaderState$RoadShaderProgram'
  'com.google.android.libraries.geo.mapcore.internal.legacy.vector.gl.drawable.GmmConfigurableTextureStyleIdShaderState$GmmConfigurableTextureStyleIdShaderProgram'
  'com.google.android.libraries.geo.mapcore.internal.legacy.vector.gl.drawable.GmmStyleIdShaderState$GmmStyleIdShaderProgram'
  'com.google.android.libraries.geo.mapcore.internal.legacy.vector.gl.drawable.GmmStyleTextureShaderState$StyleTextureShaderProgram'
  'com.google.android.libraries.geo.mapcore.internal.legacy.vector.gl.drawable.GmmTextureStyleIdShaderState$GmmTextureStyleIdShaderProgram'
  'com.google.android.libraries.geo.mapcore.internal.legacy.vector.gl.drawable.PointGeometryShaderState$PointGeometryShaderProgram'
  'com.google.android.libraries.geo.mapcore.renderer.DefaultShaderState$DefaultShaderProgram'
  'com.google.android.libraries.geo.mapcore.renderer.FrameTimeOverlay$FrameTimeOverlayShaderProgram'
  'com.google.android.libraries.geo.mapcore.renderer.TextureShaderState$TextureShaderProgram'
)

shader_class_list="$(printf '%s\n' "${shader_program_classes[@]}")"
shader_mapping_failures="$(
  awk -v class_list="$shader_class_list" '
    BEGIN {
      class_count = split(class_list, classes, "\n")
      for (i = 1; i <= class_count; i++) {
        expected[classes[i]] = 1
      }
    }
    {
      sub(/\r$/, "")
    }
    /^[^[:space:]#]/ {
      current = ""
      class_name = $0
      sub(/ -> .*/, "", class_name)
      if (class_name in expected && $0 == class_name " -> " class_name ":") {
        retained[class_name] = 1
        current = class_name
      }
      next
    }
    current != "" && /void <init>\(\).* -> <init>$/ {
      constructor[current] = 1
    }
    END {
      for (i = 1; i <= class_count; i++) {
        class_name = classes[i]
        if (!(class_name in retained)) {
          print "class name not retained: " class_name
        } else if (!(class_name in constructor)) {
          print "zero-argument constructor absent: " class_name
        }
      }
    }
  ' "$mapping_file"
)"
if [[ -n "$shader_mapping_failures" ]]; then
  echo "Navigation shader program R8 mapping audit failed:" >&2
  echo "$shader_mapping_failures" >&2
  exit 1
fi

for shader_class in "${shader_program_classes[@]}"; do
  shader_constructor_name="${shader_class##*.}"
  if ! contains_exact_line "$shader_class: $shader_constructor_name()" "$seeds_file"; then
    echo "Navigation shader program constructor is not an R8 seed: $shader_class" >&2
    exit 1
  fi

  if contains_exact_line "$shader_class" "$usage_file"; then
    echo "Navigation shader program class was removed by R8: $shader_class" >&2
    exit 1
  fi

  removed_shader_constructor="$({
    awk -v header="$shader_class:" '
      { sub(/\r$/, "") }
      $0 == header { inside = 1; next }
      inside && $0 !~ /^[[:space:]]/ { inside = 0 }
      inside && $0 ~ /^[[:space:]]+(public[[:space:]]+|protected[[:space:]]+|private[[:space:]]+)?void <init>\(\)$/ { print }
    ' "$usage_file"
  } || true)"
  if [[ -n "$removed_shader_constructor" ]]; then
    echo "Navigation shader program constructor was removed by R8: $shader_class" >&2
    exit 1
  fi
done

shader_keep_rule="$({
  awk '
    { sub(/\r$/, "") }
    $0 == "-keepclasseswithmembers class * extends com.google.android.libraries.geo.mapcore.renderer.ej {" {
      inside = 1
      next
    }
    inside && $0 == "}" { exit }
    inside { print }
  ' "$configuration_file"
} || true)"
if ! grep -Eq '^[[:space:]]*<init>\(\);[[:space:]]*$' <<<"$shader_keep_rule"; then
  echo "Missing Navigation shader-program reflection rule" >&2
  exit 1
fi

for disabled_optimization in \
  '!class/merging/horizontal' \
  '!class/merging/vertical'; do
  if ! grep -Eq "^[[:space:]]*-optimizations[[:space:]]+${disabled_optimization}([[:space:]]|$)" \
    "$configuration_file"; then
    echo "Missing Navigation SDK R8 class-merging exclusion" >&2
    exit 1
  fi
done

registry_name="com.google.android.libraries.navigation.internal.als.ax"
if ! grep -Fqx "$registry_name -> $registry_name:" "$mapping_file"; then
  echo "Navigation SDK reflective registry moved out of its package" >&2
  exit 1
fi

if grep -R -n -E --include='*.kt' 'com\.google\.android\.gms\.maps\.MapsInitializer' app/src/main; then
  echo "Application source must not call the Google MapsInitializer with Navigation SDK" >&2
  exit 1
fi

if grep -q 'com\.amap\.api:3dmap' app/build.gradle.kts; then
  expected_amap_artifact='com.amap.api:3dmap-location-search:11.2.000_loc11.2.000_sea9.8.0'
  if ! grep -Fq "$expected_amap_artifact" app/build.gradle.kts; then
    echo "AMap SDK must remain pinned to the reviewed combined artifact" >&2
    exit 1
  fi
  if ! grep -R -q -E --include='*.kt' 'import com\.amap\.api\.maps\.MapsInitializer|com\.amap\.api\.maps\.MapsInitializer' app/src/main; then
    echo "AMap SDK integration is missing its privacy initializer" >&2
    exit 1
  fi
  for privacy_call in updatePrivacyShow updatePrivacyAgree; do
    if ! grep -R -q --include='*.kt' "$privacy_call" app/src/main; then
      echo "AMap SDK integration is missing $privacy_call before initialization" >&2
      exit 1
    fi
  done
fi

echo "Navigation R8 reflection audit passed"
