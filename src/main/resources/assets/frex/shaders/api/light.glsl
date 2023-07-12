/****************************************************************
 * frex:shaders/api/light.glsl - Canvas Implementation
 ***************************************************************/

#ifdef COLORED_LIGHTS_ENABLED

#ifdef SPARSE_LIGHT_DATA
#define LightSampler sampler2D

bool _cv_hasLightData(vec3 worldPos) {
	return false;
}

vec2 _cv_lightCoords(vec3 worldPos) {
	return vec2(0.0);
}

ivec2 _cv_lightTexelCoords(vec3 worldPos) {
	return ivec2(0);
}
#else
#define LightSampler sampler3D

bool _cv_hasLightData(vec3 worldPos) {
	return clamp(worldPos, _cvu_world[_CV_LIGHT_DATA_ORIGIN].xyz, _cvu_world[_CV_LIGHT_DATA_ORIGIN].xyz + _CV_LIGHT_DATA_SIZE) == worldPos;
}

vec3 _cv_lightCoords(vec3 worldPos) {
	return mod(worldPos, _CV_LIGHT_DATA_SIZE) / _CV_LIGHT_DATA_SIZE;
}

ivec3 _cv_lightTexelCoords(vec3 worldPos) {
	return ivec3(mod(clamp(worldPos, _cvu_world[_CV_LIGHT_DATA_ORIGIN].xyz, _cvu_world[_CV_LIGHT_DATA_ORIGIN].xyz + _CV_LIGHT_DATA_SIZE), _CV_LIGHT_DATA_SIZE));
}
#endif

bool _cv_isUseful(float a) {
	return (int(a * 15.0) & 8) > 0;
}

vec4 frx_getLightFiltered(LightSampler lightSampler, vec3 worldPos) {
	if (!_cv_hasLightData(worldPos)) {
		return vec4(0.0);
	}

	#ifdef _CV_LIGHT_DATA_COMPLEX_FILTER
	vec3 pos = floor(worldPos) + vec3(0.5);
	vec3 H = sign(fract(worldPos) - vec3(0.5));
	#else
	vec3 pos = worldPos - vec3(0.5);
	const vec3 H = vec3(1.0);
	#endif

	vec4 tex000 = texelFetch(lightSampler, _cv_lightTexelCoords(pos + vec3(0.0, 0.0, 0.0)), 0);
	vec4 tex001 = texelFetch(lightSampler, _cv_lightTexelCoords(pos + vec3(0.0, 0.0, H.z)), 0);
	vec4 tex010 = texelFetch(lightSampler, _cv_lightTexelCoords(pos + vec3(0.0, H.y, 0.0)), 0);
	vec4 tex011 = texelFetch(lightSampler, _cv_lightTexelCoords(pos + vec3(0.0, H.y, H.z)), 0);
	vec4 tex101 = texelFetch(lightSampler, _cv_lightTexelCoords(pos + vec3(H.x, 0.0, H.z)), 0);
	vec4 tex110 = texelFetch(lightSampler, _cv_lightTexelCoords(pos + vec3(H.x, H.y, 0.0)), 0);
	vec4 tex100 = texelFetch(lightSampler, _cv_lightTexelCoords(pos + vec3(H.x, 0.0, 0.0)), 0);
	vec4 tex111 = texelFetch(lightSampler, _cv_lightTexelCoords(pos + vec3(H.x, H.y, H.z)), 0);

	#ifdef _CV_LIGHT_DATA_COMPLEX_FILTER
	vec3 center = worldPos - pos;
	vec3 pos000 = vec3(0.0, 0.0, 0.0) - center;
	vec3 pos001 = vec3(0.0, 0.0, H.z) - center;
	vec3 pos010 = vec3(0.0, H.y, 0.0) - center;
	vec3 pos011 = vec3(0.0, H.y, H.z) - center;
	vec3 pos101 = vec3(H.x, 0.0, H.z) - center;
	vec3 pos110 = vec3(H.x, H.y, 0.0) - center;
	vec3 pos100 = vec3(H.x, 0.0, 0.0) - center;
	vec3 pos111 = vec3(H.x, H.y, H.z) - center;

	// origin filter
	float a000 = 1.0;
	float a001 = float(_cv_isUseful(tex001.a)) * float(all(greaterThanEqual(vec3(1.05 / 15.0), abs(tex001.rgb - tex000.rgb))));
	float a010 = float(_cv_isUseful(tex010.a)) * float(all(greaterThanEqual(vec3(1.05 / 15.0), abs(tex010.rgb - tex000.rgb))));
	float a100 = float(_cv_isUseful(tex100.a)) * float(all(greaterThanEqual(vec3(1.05 / 15.0), abs(tex100.rgb - tex000.rgb))));
	float a011 = float(_cv_isUseful(tex011.a)) * float(all(greaterThanEqual(vec3(2.05 / 15.0), abs(tex011.rgb - tex000.rgb))));
	float a101 = float(_cv_isUseful(tex101.a)) * float(all(greaterThanEqual(vec3(2.05 / 15.0), abs(tex101.rgb - tex000.rgb))));
	float a110 = float(_cv_isUseful(tex110.a)) * float(all(greaterThanEqual(vec3(2.05 / 15.0), abs(tex110.rgb - tex000.rgb))));
	float a111 = float(_cv_isUseful(tex111.a)) * float(all(greaterThanEqual(vec3(3.05 / 15.0), abs(tex111.rgb - tex000.rgb))));

	float w000 = a000 * abs(pos111.x * pos111.y * pos111.z);
	float w001 = a001 * abs(pos110.x * pos110.y * pos110.z);
	float w010 = a010 * abs(pos101.x * pos101.y * pos101.z);
	float w011 = a011 * abs(pos100.x * pos100.y * pos100.z);
	float w101 = a101 * abs(pos010.x * pos010.y * pos010.z);
	float w110 = a110 * abs(pos001.x * pos001.y * pos001.z);
	float w100 = a100 * abs(pos011.x * pos011.y * pos011.z);
	float w111 = a111 * abs(pos000.x * pos000.y * pos000.z);

	float weight = w000 + w001 + w010 + w011 + w101 + w110 + w100 + w111;
	vec4 finalMix = weight == 0.0 ? vec4(0.0) : vec4((tex000.rgb * w000 + tex001.rgb * w001 + tex010.rgb * w010 + tex011.rgb * w011 + tex101.rgb * w101 + tex110.rgb * w110 + tex100.rgb * w100 + tex111.rgb * w111) / weight, 1.0);
	#else
	vec3 fac = fract(pos);

	vec3 mix001 = mix(tex000.rgb, tex001.rgb, fac.z);
	vec3 mix011 = mix(tex010.rgb, tex011.rgb, fac.z);
	vec3 mix010 = mix(mix001, mix011, fac.y);

	vec3 mix101 = mix(tex100.rgb, tex101.rgb, fac.z);
	vec3 mix111 = mix(tex110.rgb, tex111.rgb, fac.z);
	vec3 mix110 = mix(mix101, mix111, fac.y);

	vec4 finalMix = vec4(mix(mix010, mix110, fac.x), 1.0);
	#endif

	return finalMix;
}

vec4 frx_getLightRaw(LightSampler lightSampler, vec3 worldPos) {
	if (!_cv_hasLightData(worldPos)) {
		return vec4(0.0);
	}

	// TODO: use texture() for debug purpose. should be texelFetc() eventually
	vec4 tex = texture(lightSampler, _cv_lightCoords(worldPos));
	return vec4(tex.rgb, float(_cv_isUseful(tex.a)));
}

vec3 frx_getLight(LightSampler lightSampler, vec3 worldPos, vec3 fallback) {
	vec4 light = frx_getLightFiltered(lightSampler, worldPos);
	return mix(fallback, light.rgb, light.a);
}

bool frx_lightDataExists(vec3 worldPos) {
	return _cv_hasLightData(worldPos);
}

#ifdef LIGHT_DATA_HAS_OCCLUSION
struct frx_LightData {
	vec4 light;
	bool isLightSource;
	bool isOccluder;
	bool isFullCube;
};

frx_LightData frx_getLightOcclusionData(LightSampler lightSampler, vec3 worldPos) {
	if (!_cv_hasLightData(worldPos)) {
		return frx_LightData(vec4(0.0), false, false, false);
	}

	vec4 tex = texelFetch(lightSampler, _cv_lightTexelCoords(worldPos));
	int flags = int(a * 15.0);

	bool isFullCube = (flags & 4) > 0;
	bool isOccluder = (flags & 2) > 0;
	bool isLightSource = (flags & 1) > 0;

	return frx_LightData(vec4(tex.rgb, 1.0), isLightSource, isOccluder, isFullCube);
}
#endif
#endif
