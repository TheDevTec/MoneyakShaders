package com.moneyakshaders.render;

/**
 * GPU programs shared by the renderer and the real-driver regression checks.
 */
final class TerrainShaders {

	private TerrainShaders() {
	}

private static final String FS_HEADER = """
		#version 330 core
		flat in float vSceneVisibility;
		in vec3 vN;
		in vec2 vUv;
		in vec2 vLm;
		in vec4 vColor;
		in float vDist;
		in vec3 vWorldRel;
		in vec3 vShadowRel0;
		in vec3 vShadowRel1;
		in vec3 vShadowRel2;
		in vec4 vTintC;
		in vec2 vAoSway;
		flat in float vMat;
		layout(location=0) out vec4 f;
		layout(location=1) out float fReveal;

		uniform sampler2D uAtlas;
		uniform sampler2D uLightmap;
		uniform vec3 uFogColor;
		uniform int uTranslucent;
		uniform int uOit;
		uniform int uCameraUnderwater;
		uniform float uLeavesFarDist;
		uniform float uPackLodNear;
		uniform float uPackLodFar;
		uniform float uPackLodBias;
		uniform float uDepthOnly;

		uniform sampler2DShadow uShadowMap0;
		uniform sampler2DShadow uShadowMap1;
		uniform sampler2DShadow uShadowMap2;
		uniform sampler2D uShadowWaterMap0;
		uniform sampler2D uShadowWaterMap1;
		uniform sampler2D uShadowWaterMap2;
		uniform mat4 uLightMVP0;
		uniform mat4 uLightMVP1;
		uniform mat4 uLightMVP2;
		uniform float uShadowBias0;
		uniform float uShadowBias1;
		uniform float uShadowBias2;
		uniform float uShadowNormalBias0;
		uniform float uShadowNormalBias1;
		uniform float uShadowNormalBias2;
		uniform float uShadowStrength;
		uniform float uCascadeEnd0;
		uniform float uCascadeEnd1;
		uniform float uCascadeEnd2;
		uniform float uCascadeBlend0;
		uniform float uCascadeBlend1;

		uniform vec3 uDirectionalDir;
		uniform vec3 uDirectionalColor;
		uniform vec3 uSkyAmbientColor;
		uniform vec3 uShadowAmbientColor;
		uniform vec3 uHorizonFogColor;
		uniform float uDirectionalStrength;
		uniform float uSkyAmbientStrength;
		uniform float uFoliageTransmission;
		uniform float uRainFactor;

		uniform vec3 uSunDir;
		uniform vec3 uCelestialColor;
		uniform float uDayFactor;
		uniform float uCelestialMoon;
		uniform float uShadowNormalBias;
		uniform float uShadowNormalBiasFar;
		uniform float uShadowSharpDist;

		uniform sampler2D uPointShadowMap;
		uniform sampler2D uPointEntityShadowMap;
		uniform mat4 uPointMVP[48];
		uniform vec4 uPointData[8];
		uniform vec3 uPointCols[8];
		uniform float uPointDynA[8];
		uniform float uPointFade[8];
		uniform float uPointShadowStr;

		uniform vec3 uHeldLight;
		uniform float uHeldRadius;
		uniform int uLightCount;
		uniform vec4 uLights[32];
		uniform vec3 uLightCols[32];
		uniform float uOreGlow;

		uniform sampler2D uWaterSceneColor;
		uniform sampler2D uWaterSceneDepth;
		uniform sampler2D uWaterWaveTex;
		uniform float uWaterFoam;
		uniform float uWaterTransparency;
		uniform float uWaterAbsorption;
		uniform float uWaterSpecular;
		uniform mat4 uProj;
		uniform mat4 uView;
		uniform mat4 uInvProj;
		uniform vec2 uScreenSize;
		uniform vec3 uCamPos;
		uniform float uTime;
		uniform float uWaterBumpiness;
		uniform float uWaterReflection;
		uniform float uWaterRefraction;
		""";

private static final String COMMON_LIB = """
		float saturate(float v){ return clamp(v,0.0,1.0); }
		float max3(vec3 v){ return max(v.x,max(v.y,v.z)); }

		vec3 activeLightDir(){
			if(dot(uDirectionalDir,uDirectionalDir)>0.1)return normalize(uDirectionalDir);
			return normalize(uSunDir);
		}

		vec3 activeLightColor(){
			if(max3(uDirectionalColor)>0.001)return uDirectionalColor;
			return max(uCelestialColor,vec3(0.001));
		}

		float activeLightStrength(){
			if(uDirectionalStrength>0.0001)return uDirectionalStrength;
			return max(uDayFactor,0.0);
		}

		vec3 skyAmbientColor(){
			if(max3(uSkyAmbientColor)>0.001)return uSkyAmbientColor;
			return mix(vec3(0.73,0.83,1.0),vec3(0.10,0.16,0.34),uCelestialMoon);
		}

		vec3 shadowAmbientColor(){
			if(max3(uShadowAmbientColor)>0.001)return uShadowAmbientColor;
			return mix(vec3(0.48,0.61,0.84),vec3(0.07,0.12,0.27),uCelestialMoon);
		}

		float skyAmbientStrength(){
			return uSkyAmbientStrength>0.0001?uSkyAmbientStrength:1.0;
		}

		bool waterMaterial(float mat){
			return (mat>3.5&&mat<4.5)||(mat>9.5&&mat<10.5);
		}

		bool foliageMaterial(float mat){
			return mat>0.5&&mat<2.5;
		}

		bool emissiveMaterial(float mat){
			return mat>7.5&&mat<8.5;
		}

		vec4 atlasSample(vec2 uv,float dist,vec2 dx,vec2 dy){
			float span=max(1.0,uPackLodFar-uPackLodNear);
			float bias=uPackLodBias*smoothstep(uPackLodNear,uPackLodNear+span,dist);
			float scale=exp2(bias);
			return textureGrad(uAtlas,uv,dx*scale,dy*scale);
		}

		bool cutoutDiscard(float mat,float dist,float alpha){
			bool shell=mat>0.5&&mat<1.5;
			bool mid=mat>5.5&&mat<6.5;
			bool deep=mat>4.5&&mat<5.5;
			if((shell||mid||deep)&&uLeavesFarDist>0.0&&dist>uLeavesFarDist)return false;
			if(deep)return false;
			if(mid)return alpha<0.22;
			return textureLod(uAtlas,vUv,0.0).a<0.5;
		}
		""";

private static final String SHADOW_LIB = """
		const vec2 POISSON12[12]=vec2[](
			vec2(-0.326,-0.406),vec2(-0.840,-0.074),vec2(-0.696,0.457),
			vec2(-0.203,0.621),vec2(0.962,-0.195),vec2(0.473,-0.480),
			vec2(0.519,0.767),vec2(0.185,-0.893),vec2(0.507,0.064),
			vec2(0.896,0.412),vec2(-0.322,-0.933),vec2(-0.792,-0.598)
		);

		float hash12(vec2 p){
			vec3 p3=fract(vec3(p.xyx)*0.1031);
			p3+=dot(p3,p3.yzx+33.33);
			return fract((p3.x+p3.y)*p3.z);
		}

		bool shadowInside(vec3 p){
			return p.x>0.002&&p.x<0.998&&p.y>0.002&&p.y<0.998&&p.z>0.0&&p.z<1.0;
		}

		float shadowEdge(vec3 p){
			return saturate(min(min(p.x,1.0-p.x),min(p.y,1.0-p.y))/0.018);
		}

		vec3 shadowProject(mat4 m,vec3 rel,vec3 normal,float normalBias){
			vec4 p=m*vec4(rel+normal*normalBias,1.0);
			return p.xyz/max(abs(p.w),0.000001)*0.5+0.5;
		}

		float pcf4(sampler2DShadow sm,vec3 p,float bias){
			vec2 t=0.60/vec2(textureSize(sm,0));
			float z=p.z-bias;
			float v=texture(sm,vec3(p.xy+vec2(-t.x,-t.y),z));
			v+=texture(sm,vec3(p.xy+vec2(t.x,-t.y),z));
			v+=texture(sm,vec3(p.xy+vec2(-t.x,t.y),z));
			v+=texture(sm,vec3(p.xy+vec2(t.x,t.y),z));
			return v*0.25;
		}

		float pcfPoisson(sampler2DShadow sm,vec3 p,float bias,float radius,int samples,vec3 world){
			vec2 texel=radius/vec2(textureSize(sm,0));
			float rot=hash12(floor(world.xz*4.0))*6.2831853;
			float s=sin(rot),c=cos(rot);
			mat2 r=mat2(c,-s,s,c);
			float v=0.0;
			for(int i=0;i<12;i++){
				if(i>=samples)break;
				v+=texture(sm,vec3(p.xy+(r*POISSON12[i])*texel,p.z-bias));
			}
			return v/float(samples);
		}

		float cascade0Visibility(vec3 N,float slope){
			float nb=uShadowNormalBias0>0.0?uShadowNormalBias0:uShadowNormalBias;
			vec3 p=shadowProject(uLightMVP0,vShadowRel0,N,nb);
			if(!shadowInside(p))return 1.0;
			float v=pcf4(uShadowMap0,p,uShadowBias0*slope);
			return mix(1.0,v,shadowEdge(p));
		}

		float cascade1Visibility(vec3 N,float slope){
			float nb=uShadowNormalBias1>0.0?uShadowNormalBias1:uShadowNormalBiasFar;
			vec3 p=shadowProject(uLightMVP1,vShadowRel1,N,nb);
			if(!shadowInside(p))return 1.0;
			float v=pcfPoisson(uShadowMap1,p,uShadowBias1*slope,1.15,8,vWorldRel+uCamPos);
			return mix(1.0,v,shadowEdge(p));
		}

		float cascade2Visibility(vec3 N,float slope){
			vec3 p=shadowProject(uLightMVP2,vShadowRel2,N,uShadowNormalBias2);
			if(!shadowInside(p))return 1.0;
			float v=pcfPoisson(uShadowMap2,p,uShadowBias2*slope,1.85,12,vWorldRel+uCamPos);
			return mix(1.0,v,shadowEdge(p));
		}

		float directionalVisibility(vec3 N){
			if(uShadowStrength<=0.0001)return 1.0;
			vec3 L=activeLightDir();
			float ndl=max(dot(N,L),0.0);
			if(ndl<=0.001)return 1.0;
			float x=1.0-ndl;
			float slope=1.0+1.7*x*x;

			if(uCascadeEnd2<=0.0){
				float a=cascade0Visibility(N,slope);
				float b=cascade1Visibility(N,slope);
				float split=max(uShadowSharpDist,1.0);
				float t=smoothstep(split*0.75,split*1.35,vDist);
				return mix(a,b,t);
			}

			if(vDist<uCascadeEnd0){
				float a=cascade0Visibility(N,slope);
				float w=max(uCascadeBlend0,1.0);
				float t=smoothstep(uCascadeEnd0-w,uCascadeEnd0,vDist);
				return t>0.0?mix(a,cascade1Visibility(N,slope),t):a;
			}

			if(vDist<uCascadeEnd1){
				float a=cascade1Visibility(N,slope);
				float w=max(uCascadeBlend1,1.0);
				float t=smoothstep(uCascadeEnd1-w,uCascadeEnd1,vDist);
				return t>0.0?mix(a,cascade2Visibility(N,slope),t):a;
			}

			if(vDist<uCascadeEnd2)return cascade2Visibility(N,slope);
			return 1.0;
		}

		float waterCoverageSample(sampler2D map,vec3 p){
			if(!shadowInside(p))return 0.0;
			return 1.0-smoothstep(0.30,0.72,texture(map,p.xy).r);
		}

		float directionalWaterCoverage(vec3 N){
			if(uCameraUnderwater==0)return 0.0;
			vec3 p0=shadowProject(uLightMVP0,vShadowRel0,N,uShadowNormalBias0);
			vec3 p1=shadowProject(uLightMVP1,vShadowRel1,N,uShadowNormalBias1);
			if(uCascadeEnd2<=0.0){
				float a=waterCoverageSample(uShadowWaterMap0,p0);
				float b=waterCoverageSample(uShadowWaterMap1,p1);
				return mix(a,b,smoothstep(uShadowSharpDist*0.75,uShadowSharpDist*1.35,vDist));
			}
			vec3 p2=shadowProject(uLightMVP2,vShadowRel2,N,uShadowNormalBias2);
			if(vDist<uCascadeEnd0){
				float t=smoothstep(uCascadeEnd0-max(uCascadeBlend0,1.0),uCascadeEnd0,vDist);
				return mix(waterCoverageSample(uShadowWaterMap0,p0),waterCoverageSample(uShadowWaterMap1,p1),t);
			}
			if(vDist<uCascadeEnd1){
				float t=smoothstep(uCascadeEnd1-max(uCascadeBlend1,1.0),uCascadeEnd1,vDist);
				return mix(waterCoverageSample(uShadowWaterMap1,p1),waterCoverageSample(uShadowWaterMap2,p2),t);
			}
			return waterCoverageSample(uShadowWaterMap2,p2);
		}
		""";

private static final String WATER_LIB = """
		vec3 viewFromDepth(vec2 uv,float depth){
			vec4 p=uInvProj*vec4(uv*2.0-1.0,depth*2.0-1.0,1.0);
			return p.xyz/max(abs(p.w),0.000001);
		}

		vec3 waterNormal(vec3 baseN,vec3 world,float fresnel){
			if(abs(baseN.y)<0.45)return baseN;
			vec2 p0=world.xz*0.018+vec2(uTime*0.014,-uTime*0.009);
			vec2 p1=world.xz*0.046+vec2(-uTime*0.008,uTime*0.012);
			vec2 a=texture(uWaterWaveTex,p0).rg*2.0-1.0;
			vec2 b=texture(uWaterWaveTex,p1).rg*2.0-1.0;
			vec2 w=(a*0.64+b*0.36)*0.150*uWaterBumpiness*(1.0-fresnel*0.32);
			return normalize(vec3(w.x,sign(baseN.y),w.y));
		}

		vec3 waterSky(vec3 reflectedView){
			vec3 rw=normalize(transpose(mat3(uView))*reflectedView);
			float h=saturate(rw.y*0.5+0.5);
			vec3 horizon=mix(shadowAmbientColor(),skyAmbientColor(),0.62);
			vec3 zenith=skyAmbientColor()*0.70;
			vec3 sky=mix(horizon,zenith,pow(h,0.72));
			vec3 L=activeLightDir();
			float disc=pow(max(dot(rw,L),0.0),420.0);
			return sky+activeLightColor()*disc*activeLightStrength()*4.0;
		}

		float waterSpecular(vec3 N,vec3 V,vec3 L){
			float nl=max(dot(N,L),0.0);
			float nv=max(dot(N,V),0.001);
			if(nl<=0.0)return 0.0;
			vec3 H=normalize(V+L);
			float nh=max(dot(N,H),0.0);
			float vh=max(dot(V,H),0.0);
			float rough=0.12;
			float a=rough*rough,a2=a*a;
			float d=nh*nh*(a2-1.0)+1.0;
			float D=a2/(3.14159265*d*d);
			float k=(rough+1.0);
			k=k*k*0.125;
			float G=(nv/(nv*(1.0-k)+k))*(nl/(nl*(1.0-k)+k));
			float F=0.02+0.98*pow(1.0-vh,5.0);
			return D*G*F/max(4.0*nv*nl,0.001);
		}

		float underwaterSurfacePattern(vec3 world,vec3 N){
			if(uCameraUnderwater==0||N.y<0.18)return 0.0;
			vec2 slow=world.xz*0.062+vec2(uTime*0.011,-uTime*0.008);
			vec2 fast=world.xz*0.119+vec2(-uTime*0.017,uTime*0.013);
			float broad=texture(uWaterWaveTex,slow).a;
			float detail=texture(uWaterWaveTex,fast).b;
			return smoothstep(0.50,0.76,broad*0.62+detail*0.56)*N.y;
		}

		vec4 shadeWater(vec3 N){
			vec3 world=vWorldRel+uCamPos;
			vec3 V=normalize(-vWorldRel);
			float f0=pow(1.0-saturate(abs(dot(N,V))),5.0);
			N=waterNormal(N,world,f0);
			float ndv=saturate(abs(dot(N,V)));
			float fresnel=0.02+0.98*pow(1.0-ndv,5.0);

			vec2 uv=(gl_FragCoord.xy+vec2(0.5))/max(uScreenSize,vec2(1.0));
			float depth=texture(uWaterSceneDepth,uv).r;
			vec3 viewPos=(uView*vec4(vWorldRel,1.0)).xyz;
			vec3 behind=depth<0.99997?viewFromDepth(uv,depth):viewPos;
			float thickness=clamp(length(behind)-length(viewPos),0.0,48.0);

			vec3 viewN=normalize(mat3(uView)*N);
			// Strong close refraction turns the surface into a blurred copy of the bed.
			// Keep the distortion small enough for the water body and ripples to read.
			float refrAmount=(1.0-exp(-thickness*0.18))*uWaterRefraction;
			vec2 offset=viewN.xy*(0.00135/(0.5+abs(viewN.z)))*refrAmount/(1.0+vDist*0.02);
			vec2 refrUv=clamp(uv+offset,vec2(0.003),vec2(0.997));
			float refrDepth=texture(uWaterSceneDepth,refrUv).r;
			if(refrDepth<=gl_FragCoord.z+0.00004)refrUv=uv;

			vec3 transmitted=texture(uWaterSceneColor,refrUv).rgb;
			vec3 shallow=vec3(0.018,0.205,0.335);
			vec3 deep=vec3(0.008,0.052,0.115);
			float absorptionScale=mix(0.35,1.65,saturate(uWaterAbsorption));
			vec3 absorption=exp(-thickness*vec3(0.110,0.050,0.026)*absorptionScale);
			vec3 scatter=mix(shallow,deep,smoothstep(2.0,20.0,thickness));
			vec3 refracted=transmitted*absorption+scatter*(1.0-absorption)*0.85;
			float waterBody=0.18+(1.0-exp(-thickness*0.16))*0.40*saturate(uWaterAbsorption);
			refracted=mix(refracted,shallow,waterBody);
			// Colour variation comes from two independently moving frequencies, not a 1-D
			// brightness multiplier: this keeps a readable blue/cyan body without striping.
			float broadColor=texture(uWaterWaveTex,world.xz*0.014+vec2(uTime*0.004,-uTime*0.003)).a;
			float fineColor=texture(uWaterWaveTex,world.xz*0.083+vec2(-uTime*0.011,uTime*0.008)).b;
			float colorVariation=broadColor*0.62+fineColor*0.38;
			refracted=mix(refracted,vec3(0.018,0.26,0.40),saturate((colorVariation-0.42)*0.34));

			vec3 reflected=waterSky(normalize(reflect(normalize(viewPos),viewN)));
			float reflection=saturate(fresnel*(0.28+0.42*uWaterReflection)+0.015*uWaterReflection);
			if(uCameraUnderwater==1)reflection*=0.22;
			vec3 surface=mix(refracted,reflected,reflection);
			float shore=smoothstep(0.10,0.32,thickness)*(1.0-smoothstep(0.35,0.95,thickness));
			float foamNoise=texture(uWaterWaveTex,world.xz*0.075+vec2(uTime*0.009,-uTime*0.007)).b;
			float foam=shore*smoothstep(0.66,0.84,foamNoise)*saturate(uWaterFoam);
			surface=mix(surface,vec3(0.78,0.92,0.94),foam*0.32);

			vec3 L=activeLightDir();
			float spec=waterSpecular(N,V,L)*activeLightStrength()*directionalVisibility(N)*mix(0.25,1.55,saturate(uWaterSpecular));
			surface+=activeLightColor()*min(spec,1.8);
			float nightWater=saturate(1.0-uDayFactor*3.0);
			if(uCelestialMoon>0.5)nightWater=max(nightWater,0.65);
			surface*=mix(vec3(1.0),vec3(0.52,0.68,0.82),nightWater);
			surface*=mix(1.0,0.68,nightWater);

			float opacity=mix(0.96,0.48,saturate(uWaterTransparency));
			float alpha=clamp(opacity+(1.0-exp(-thickness*0.10))*0.24+fresnel*0.10+foam*0.12,0.46,0.98);
			if(uCameraUnderwater==1)alpha=clamp(alpha*0.82,0.42,0.78);
			return vec4(max(surface,vec3(0.0)),alpha);
		}
		""";

private static final String POINT_LIGHT_LIB = """
		int pointFace(vec3 p){
			vec3 a=abs(p);
			if(a.x>=a.y&&a.x>=a.z)return p.x>0.0?0:1;
			if(a.y>=a.z)return p.y>0.0?2:3;
			return p.z>0.0?4:5;
		}

		float pointDepthVisibility(int slot,vec3 receiver,vec3 N){
			vec3 p=receiver-uPointData[slot].xyz;
			int face=pointFace(p);
			vec4 lp=uPointMVP[slot*6+face]*vec4(p+N*0.003,1.0);
			if(lp.w<=0.0)return 1.0;

			vec3 pc=lp.xyz/lp.w*0.5+0.5;
			if(pc.z<=0.0||pc.z>=1.0)return 1.0;

			float cellX=float(face%3),cellY=float(face/3+slot*2);
			vec2 minUv=vec2(cellX/3.0,cellY/16.0);
			vec2 maxUv=vec2((cellX+1.0)/3.0,(cellY+1.0)/16.0);
			vec2 texel=1.0/vec2(textureSize(uPointShadowMap,0));
			minUv+=texel;
			maxUv-=texel;

			vec2 uv=clamp(vec2((pc.x+cellX)/3.0,(pc.y+cellY)/16.0),minUv,maxUv);
			vec3 L=normalize(uPointData[slot].xyz-receiver);
			float bias=0.00020+0.0012*(1.0-max(dot(N,L),0.0));
			float result=0.0;
			vec2 o=texel*0.70;

			for(int y=-1;y<=1;y+=2){
				for(int x=-1;x<=1;x+=2){
					vec2 s=clamp(uv+vec2(x,y)*o,minUv,maxUv);
					float d=min(texture(uPointShadowMap,s).r,texture(uPointEntityShadowMap,s).r);
					result+=step(pc.z-bias,d);
				}
			}
			return result*0.25;
		}

		// LocalLightSet is the only local-direct energy path.  Point shadows modulate
		// this exact contribution; they never add a second light.
		vec3 localDirect(vec3 N,vec3 albedo){
			vec3 result=vec3(0.0);
			for(int i=0;i<8;i++){
				float r=uPointData[i].w;
				if(r<=0.01)continue;
				vec3 delta=uPointData[i].xyz-vWorldRel;
				float d2=dot(delta,delta);
				if(d2>=r*r)continue;
				float d=sqrt(max(d2,0.0001));
				vec3 L=delta/d;
				float ndl=max(dot(N,L),0.0);
				float att=1.0-d2/(r*r);
				att*=att;
				float visibility=mix(1.0,pointDepthVisibility(i,vWorldRel,N),saturate(uPointShadowStr));
				result+=albedo*uPointCols[i]*att*(0.18+0.82*ndl)*visibility*uPointFade[i]*0.52;
			}
			return result;
		}
		""";

private static final String MAIN_FS = """
		void main(){
			vec3 N=normalize(vN);
			bool water=waterMaterial(vMat);
			vec2 uvDx=dFdx(vUv),uvDy=dFdy(vUv);
			vec4 tex=atlasSample(vUv,vDist,uvDx,uvDy);

			if(uDepthOnly>0.5){
				if(cutoutDiscard(vMat,vDist,tex.a))discard;
				f=vec4(0.0);
				fReveal=0.0;
				return;
			}

			if(uTranslucent==0&&cutoutDiscard(vMat,vDist,tex.a))discard;
			// Residency is appearance only: opaque terrain is dithered, never mixed with fog.
			float reveal=saturate(vSceneVisibility);
			float dither=fract(sin(dot(gl_FragCoord.xy,vec2(12.9898,78.233)))*43758.5453);
			if(uTranslucent==0&&dither>reveal)discard;

			if(uTranslucent==1&&water){
				vec4 wt=shadeWater(N);
				wt.a*=reveal;
				if(uOit==1){
					float w=mix(0.30,1.0,wt.a);
					f=vec4(wt.rgb*wt.a*w,wt.a*w);
					fReveal=wt.a;
				}else{
					f=wt;
					fReveal=0.0;
				}
				return;
			}

			vec3 albedo=tex.rgb*vColor.rgb;
			float alpha=uTranslucent==1?tex.a*vColor.a*reveal:1.0;
			if(uTranslucent==1&&alpha<=0.003)discard;

			float sky=saturate(vLm.y*1.25-0.08);
			float block=saturate(vLm.x*1.25-0.08);
			vec3 ambientColor=mix(shadowAmbientColor(),skyAmbientColor(),0.30+sky*0.70);
			float ambientStrength=skyAmbientStrength()*(0.055+sky*0.945);
			float ao=saturate(vAoSway.x);
			vec3 skyIndirect=albedo*ambientColor*ambientStrength*ao;
			// Propagated block-light scalar owns local-indirect energy.  Baked tint is
			// chromaticity/confidence only, so it cannot brighten an already-lit surface.
			vec3 lightChroma=vec3(1.0);
			if(vTintC.a>0.001){
				vec3 hue=max(vTintC.rgb/vTintC.a,vec3(0.0));
				lightChroma=mix(vec3(1.0),hue,saturate(vTintC.a)*0.45);
			}
			vec3 localIndirect=albedo*block*lightChroma*ao*0.62;
			vec3 col=skyIndirect+localIndirect;

			vec3 L=activeLightDir();
			float ndl=max(dot(N,L),0.0);
			float visibility=directionalVisibility(N);
			float skyGate=smoothstep(0.03,0.28,sky);
			float directStrength=activeLightStrength()*skyGate;
			vec3 directColor=activeLightColor();

			col+=albedo*directColor*directStrength*ndl*visibility;

			if(foliageMaterial(vMat)){
				float back=max(dot(-N,L),0.0);
				float transmission=clamp(uFoliageTransmission,0.0,0.07);
				col+=albedo*directColor*directStrength*back*visibility*transmission;
			}

			col+=localDirect(N,albedo);

			if(uOreGlow>0.0&&vMat>2.5&&vMat<3.5){
				float mx=max3(albedo),mn=min(albedo.r,min(albedo.g,albedo.b));
				float sat=mx>0.001?(mx-mn)/mx:0.0;
				col+=albedo*uOreGlow*smoothstep(0.12,0.30,sat)*0.20;
			}

			if(emissiveMaterial(vMat))col+=albedo*0.35;

			// Water caustics belong to submerged opaque terrain. Applying them to transparent
			// stained glass turns each pane into a striped projection screen.
			float underwaterPattern=uTranslucent==0?underwaterSurfacePattern(vWorldRel+uCamPos,N):0.0;
			col+=vec3(0.12,0.32,0.28)*underwaterPattern*0.26;

			col=max(col,vec3(0.0));

			if(uOit==1&&uTranslucent==1){
				float w=mix(0.30,1.0,alpha);
				f=vec4(col*alpha*w,alpha*w);
				fReveal=alpha;
			}else{
				f=vec4(col,alpha);
				fReveal=0.0;
			}
		}
		""";

static final String VS = """
		#version 330 core
		layout(location=0) in vec3 aPosRaw;
		layout(location=1) in vec3 aNormal;
		layout(location=2) in vec2 aUv;
		layout(location=3) in vec2 aLight;
		layout(location=4) in vec4 aColor;
		layout(location=5) in vec4 aSectionBase;
		layout(location=6) in float aMaterial;
		layout(location=7) in vec4 aLightTint;
		layout(location=8) in vec2 aAoSway;

		uniform mat4 uProj;
		uniform mat4 uView;
		uniform vec3 uCamPos;
		uniform float uTime;
		uniform float uWind;
		uniform vec3 uShadowOffset0;
		uniform vec3 uShadowOffset1;
		uniform vec3 uShadowOffset2;

		flat out float vSceneVisibility;
		out vec3 vN;
		out vec2 vUv;
		out vec2 vLm;
		out vec4 vColor;
		out float vDist;
		out vec3 vWorldRel;
		out vec3 vShadowRel0;
		out vec3 vShadowRel1;
		out vec3 vShadowRel2;
		out vec4 vTintC;
		out vec2 vAoSway;
		flat out float vMat;

		void main(){
			vec3 local=aPosRaw/256.0-8.0;
			vec3 worldRel=aSectionBase.xyz+local;

			if(uWind>0.0&&((aMaterial>0.5&&aMaterial<2.5)||(aMaterial>7.5&&aMaterial<8.5)||(aMaterial>10.5&&aMaterial<11.5))){
				vec3 world=worldRel+uCamPos;
				float phase=uTime*1.5+world.x*0.5+world.z*0.5;
				worldRel.x+=sin(phase)*uWind*aAoSway.y;
				worldRel.z+=cos(phase*0.8)*uWind*0.6*aAoSway.y;
			}

			vSceneVisibility=aSectionBase.w;
			vWorldRel=worldRel;
			vShadowRel0=worldRel+uShadowOffset0;
			vShadowRel1=worldRel+uShadowOffset1;
			vShadowRel2=worldRel+uShadowOffset2;
			vN=aNormal;
			vUv=aUv;
			vLm=(aLight+8.0)/256.0;
			vColor=aColor;
			vDist=length(worldRel);
			vMat=aMaterial;
			vTintC=aLightTint;
			vAoSway=aAoSway;
			gl_Position=uProj*uView*vec4(worldRel,1.0);
		}
		""";

static final String FS = FS_HEADER+COMMON_LIB+SHADOW_LIB+WATER_LIB+POINT_LIGHT_LIB+MAIN_FS;

	/*
	 * Depth-only shader for sun / point terrain casters.
	 */
	static final String SHADOW_VS = "#version 330 core\n"

			+ "layout(location=0) in vec3 aPosRaw;\n"
			+ "layout(location=2) in vec2 aUv;\n"
			+ "layout(location=4) in vec4 aColor;\n"
			+ "layout(location=5) in vec3 aSectionBase;\n"
			+ "layout(location=6) in float aMaterial;\n"
			+ "layout(location=8) in vec2 aAoSway;\n"

			+ "uniform mat4 uLightMVP;\n"
			+ "uniform vec3 uCamPos;\n"
			+ "uniform vec3 uCamPosLow;\n"
			+ "uniform float uTime;\n"
			+ "uniform float uWind;\n"

			+ "out vec2 vUv;\n"
			+ "flat out float vMat;\n"
			+ "out float vLDist;\n"
			+ "out vec3 vLRel;\n"
			+ "out vec3 vWorldPos;\n"

			+ "void main(){\n"

			+ "  vec3 local = aPosRaw / 256.0 - 8.0;\n"

			+ "  vec3 worldRel =\n"
			+ "      (aSectionBase - uCamPos)\n"
			+ "      + local\n"
			+ "      - uCamPosLow;\n"

			+ "  if (uWind > 0.0) {\n"

			+ "    if ((aMaterial > 0.5 && aMaterial < 2.5)\n"
			+ "        || (aMaterial > 10.5 && aMaterial < 11.5)) {\n"

			+ "      vec3 world = aSectionBase + local;\n"

			+ "      float sway = aAoSway.y;\n"

			+ "      float ph =\n"
			+ "          uTime * 1.5\n"
			+ "          + world.x * 0.5\n"
			+ "          + world.z * 0.5;\n"

			+ "      worldRel.x += sin(ph) * uWind * sway;\n"
			+ "      worldRel.z += cos(ph * 0.8) * uWind * 0.6 * sway;\n"

			+ "    }\n"

			+ "  }\n"

			+ "  vUv = aUv;\n"
			+ "  vMat = aMaterial;\n"

			+ "  vLDist = length(worldRel);\n"
			+ "  vLRel = worldRel;\n"
			+ "  vWorldPos = aSectionBase + local;\n"

			+ "  gl_Position = uLightMVP * vec4(worldRel, 1.0);\n"

			+ "}\n";

	static final String SHADOW_FS = "#version 330 core\n"

			+ "in vec2 vUv;\n"
			+ "flat in float vMat;\n"
			+ "in float vLDist;\n"
			+ "in vec3 vLRel;\n"

			+ "uniform sampler2D uAtlas;\n"
			+ "uniform float uNearCut;\n"

			+ "layout(location=0) out float oCaster;\n"

			+ "void main(){\n"

			+ "  if (uNearCut > 0.0\n"
			+ "      && vMat > 11.5\n"
			+ "      && vMat < 12.5\n"
			+ "      && all(lessThanEqual(\n"
			+ "          abs(vLRel),\n"
			+ "          vec3(0.501))))\n"
			+ "    discard;\n"

			/*
			 * 7 = no-shadow
			 * 8 = emissive+sway
			 */
			+ "  if (vMat > 6.5 && vMat < 8.5)\n"
			+ "    discard;\n"

			+ "  if (texture(uAtlas, vUv).a < 0.5)\n"
			+ "    discard;\n"

			+ "  oCaster = 0.0;\n"

			+ "}\n";

	/* Water coverage rendered beside the directional depth map. R=coverage marker; G/B reserved. */
	static final String WATER_SHADOW_FS = "#version 330 core\n"
			+ "in vec2 vUv; flat in float vMat; in vec3 vWorldPos;\n"
			+ "uniform sampler2D uWaterWaveTex; uniform float uTime;\n"
			+ "layout(location=0) out vec4 oWater;\n"
			+ "void main(){\n"
			+ "  bool water=(vMat>3.5&&vMat<4.5)||(vMat>9.5&&vMat<10.5); if(!water)discard;\n"
			+ "  oWater=vec4(0.48,0.0,0.0,1.0);\n"
			+ "}\n";

	/*
	 * Entity bounding-box caster.
	 */
	static final String BOX_VS = "#version 330 core\n"

			+ "layout(location=0) in vec3 aPos;\n"

			+ "uniform mat4 uLightMVP;\n"
			+ "uniform vec3 uCamPos;\n"

			+ "out float vLDist;\n"

			+ "void main(){\n"

			+ "  vec3 rel = aPos - uCamPos;\n"

			+ "  vLDist = length(rel);\n"

			+ "  gl_Position =\n"
			+ "      uLightMVP\n"
			+ "      * vec4(rel, 1.0);\n"

			+ "}\n";

	/*
	 * Item caster carries atlas UV so alpha can shape the shadow.
	 */
	static final String ITEM_SHADOW_VS = "#version 330 core\n"

			+ "layout(location=0) in vec3 aPos;\n"
			+ "layout(location=1) in vec2 aUv;\n"

			+ "uniform mat4 uLightMVP;\n"
			+ "uniform vec3 uCamPos;\n"

			+ "out vec2 vUv;\n"
			+ "out float vLDist;\n"

			+ "void main(){\n"

			+ "  vUv = aUv;\n"

			+ "  vec3 rel = aPos - uCamPos;\n"

			+ "  vLDist = length(rel);\n"

			+ "  gl_Position =\n"
			+ "      uLightMVP\n"
			+ "      * vec4(rel, 1.0);\n"

			+ "}\n";

	static final String ITEM_SHADOW_FS = "#version 330 core\n"

			+ "in vec2 vUv;\n"
			+ "in float vLDist;\n"

			+ "uniform sampler2D uAtlas;\n"
			+ "uniform float uNearCut;\n"

			+ "layout(location=0) out float oCaster;\n"

			+ "void main(){\n"

			+ "  if (uNearCut > 0.0\n"
			+ "      && vLDist < uNearCut)\n"
			+ "    discard;\n"

			+ "  if (textureLod(uAtlas, vUv, 0.0).a < 0.5)\n"
			+ "    discard;\n"

			+ "  oCaster = 1.0;\n"

			+ "}\n";

	/*
	 * Solid entity-box caster.
	 */
	static final String BOX_FS = "#version 330 core\n"

			+ "in float vLDist;\n"

			+ "uniform float uNearCut;\n"

			+ "layout(location=0) out float oCaster;\n"

			+ "void main(){\n"

			+ "  if (uNearCut > 0.0\n"
			+ "      && vLDist < uNearCut)\n"
			+ "    discard;\n"

			+ "  oCaster = 1.0;\n"

			+ "}\n";
}
