package com.moneyakshaders.render;

/** World-space sun/moon volumetric lighting used by {@link PostProcess}. */
final class AtmosphericShafts {
	private AtmosphericShafts() {}

	static final String FS = """
			#version 330 core
			in vec2 vUv;
			layout(location=0) out vec4 f;

			uniform sampler2D uDepth;
			uniform sampler2DShadow uShadow0;
			uniform sampler2DShadow uShadow1;
			uniform sampler2DShadow uShadow2;
			uniform sampler2D uWaterShadow0;
			uniform sampler2D uWaterShadow1;
			uniform sampler2D uWaterShadow2;

			uniform mat4 uLightMVP0;
			uniform mat4 uLightMVP1;
			uniform mat4 uLightMVP2;
			uniform mat4 uInvProj;
			uniform mat4 uInvView;

			uniform vec3 uShadowOffset0;
			uniform vec3 uShadowOffset1;
			uniform vec3 uShadowOffset2;
			uniform vec3 uCameraWorld;
			uniform vec3 uLightDir;
			uniform vec3 uColor;

			uniform float uStrength;
			uniform float uMoon;
			uniform float uWater;
			uniform float uTime;
			uniform float uNear;
			uniform float uFar;
			uniform float uCascadeEnd0;
			uniform float uCascadeEnd1;
			uniform float uCascadeEnd2;
			uniform float uCascadeBlend0;
			uniform float uCascadeBlend1;
			uniform float uDensity;
			uniform float uRainFactor;
			uniform int uDebug;

			const int MAX_STEPS=16;
			const float PI=3.14159265359;

			float saturate(float x){
				return clamp(x,0.0,1.0);
			}

			float hash12(vec2 p){
				vec3 p3=fract(vec3(p.xyx)*0.1031);
				p3+=dot(p3,p3.yzx+33.33);
				return fract((p3.x+p3.y)*p3.z);
			}

			float valueNoise2(vec2 p){
				vec2 i=floor(p),q=fract(p);
				q=q*q*(3.0-2.0*q);
				float a=hash12(i);
				float b=hash12(i+vec2(1.0,0.0));
				float c=hash12(i+vec2(0.0,1.0));
				float d=hash12(i+vec2(1.0,1.0));
				return mix(mix(a,b,q.x),mix(c,d,q.x),q.y);
			}

			float densityNoise(vec3 wp){
				vec2 p=wp.xz*0.010+vec2(uTime*0.0035,-uTime*0.0015);
				float a=valueNoise2(p);
				float b=valueNoise2(p*2.07+vec2(17.3,9.7));
				float c=valueNoise2(p*4.13-vec2(5.1,21.8));
				return mix(0.72,1.16,a*0.58+b*0.29+c*0.13);
			}

			bool insideShadow(vec3 p){
				return p.x>0.002&&p.x<0.998&&p.y>0.002&&p.y<0.998&&p.z>0.0&&p.z<1.0;
			}

			float shadowEdge(vec3 p){
				return saturate(min(min(p.x,1.0-p.x),min(p.y,1.0-p.y))/0.018);
			}

			vec3 projectShadow(mat4 m,vec3 rel,vec3 offset){
				vec4 p=m*vec4(rel+offset,1.0);
				return p.xyz/max(abs(p.w),0.000001)*0.5+0.5;
			}

			float sampleShadow0(vec3 rel,out float water){
				vec3 p=projectShadow(uLightMVP0,rel,uShadowOffset0);
				if(!insideShadow(p)){
					water=0.0;
					return 1.0;
				}
				water=1.0-smoothstep(0.30,0.72,texture(uWaterShadow0,p.xy).r);
				float visibility=texture(uShadow0,vec3(p.xy,p.z-0.00015));
				return mix(1.0,visibility,shadowEdge(p));
			}

			float sampleShadow1(vec3 rel,out float water){
				vec3 p=projectShadow(uLightMVP1,rel,uShadowOffset1);
				if(!insideShadow(p)){
					water=0.0;
					return 1.0;
				}
				water=1.0-smoothstep(0.30,0.72,texture(uWaterShadow1,p.xy).r);
				float visibility=texture(uShadow1,vec3(p.xy,p.z-0.00010));
				return mix(1.0,visibility,shadowEdge(p));
			}

			float sampleShadow2(vec3 rel,out float water){
				vec3 p=projectShadow(uLightMVP2,rel,uShadowOffset2);
				if(!insideShadow(p)){
					water=0.0;
					return 1.0;
				}
				water=1.0-smoothstep(0.30,0.72,texture(uWaterShadow2,p.xy).r);
				float visibility=texture(uShadow2,vec3(p.xy,p.z-0.00007));
				return mix(1.0,visibility,shadowEdge(p));
			}

			float sampleDirectionalShadow(vec3 rel,float dist,out float water){
				if(uCascadeEnd2<=0.0){
					float wa,wb;
					float a=sampleShadow0(rel,wa);
					float b=sampleShadow1(rel,wb);
					float split=max(uCascadeEnd0,48.0);
					float t=smoothstep(split*0.80,split,dist);
					water=mix(wa,wb,t);
					return mix(a,b,t);
				}

				if(dist<uCascadeEnd0){
					float wa,wb;
					float a=sampleShadow0(rel,wa);
					float width=max(uCascadeBlend0,1.0);
					float t=smoothstep(uCascadeEnd0-width,uCascadeEnd0,dist);
					if(t<=0.0){
						water=wa;
						return a;
					}
					float b=sampleShadow1(rel,wb);
					water=mix(wa,wb,t);
					return mix(a,b,t);
				}

				if(dist<uCascadeEnd1){
					float wa,wb;
					float a=sampleShadow1(rel,wa);
					float width=max(uCascadeBlend1,1.0);
					float t=smoothstep(uCascadeEnd1-width,uCascadeEnd1,dist);
					if(t<=0.0){
						water=wa;
						return a;
					}
					float b=sampleShadow2(rel,wb);
					water=mix(wa,wb,t);
					return mix(a,b,t);
				}

				if(dist<uCascadeEnd2)return sampleShadow2(rel,water);
				water=0.0;
				return 1.0;
			}

			float henyeyGreenstein(float mu,float g){
				float gg=g*g;
				return (1.0-gg)/(4.0*PI*pow(max(1.0+gg-2.0*g*mu,0.001),1.5));
			}

			float atmosphereDensity(vec3 wp,float distanceFromCamera){
				float relativeHeight=wp.y-uCameraWorld.y;
				float heightFactor=exp(-relativeHeight*0.0025);
				heightFactor=clamp(heightFactor,0.45,1.75);

				float nearFade=smoothstep(1.5,7.0,distanceFromCamera);
				float weather=mix(1.0,1.32,saturate(uRainFactor));
				float base=uDensity>0.0001?uDensity:1.0;
				return 0.0080*base*heightFactor*densityNoise(wp)*nearFade*weather;
			}

			vec3 reconstructView(float depth){
				vec4 p=uInvProj*vec4(vUv*2.0-1.0,depth*2.0-1.0,1.0);
				return p.xyz/max(abs(p.w),0.000001);
			}

			void main(){
				if(uStrength<=0.0001){
					f=vec4(0.0);
					return;
				}

				vec2 ndc=vUv*2.0-1.0;
				vec4 farPoint=uInvProj*vec4(ndc,1.0,1.0);
				if(abs(farPoint.w)<0.00001){
					f=vec4(0.0);
					return;
				}

				farPoint/=farPoint.w;
				vec3 rayView=normalize(farPoint.xyz);
				vec3 rayWorld=normalize((uInvView*vec4(rayView,0.0)).xyz);
				vec3 L=normalize(uLightDir);

				float rawDepth=texture(uDepth,vUv).r;
				float maxTravel=mix(224.0,54.0,uWater);
				float rayEnd=maxTravel;

				if(rawDepth<0.99995){
					vec3 surfaceView=reconstructView(rawDepth);
					rayEnd=min(maxTravel,max(length(surfaceView),0.0));
				}

				float start=mix(1.5,0.25,uWater);
				float travel=rayEnd-start;
				if(travel<=0.01){
					f=vec4(0.0);
					return;
				}

				int steps=uWater>0.5?8:14;
				float stepLength=travel/float(steps);
				float jitter=hash12(gl_FragCoord.xy)-0.5;
				float mu=clamp(dot(rayWorld,L),-1.0,1.0);

				float g=mix(0.58,0.28,uMoon);
				if(uWater>0.5)g=0.18;
				float phase=henyeyGreenstein(mu,g);
				phase*=mix(1.0,0.72,uMoon);

				float transmittance=1.0;
				float accumulated=0.0;
				float meanVisibility=0.0;
				float meanWater=0.0;

				for(int i=0;i<MAX_STEPS;i++){
					if(i>=steps)break;

					float dist=start+(float(i)+0.5+jitter*0.55)*stepLength;
					dist=clamp(dist,start,rayEnd);
					vec3 rel=rayWorld*dist;
					vec3 wp=uCameraWorld+rel;

					float waterMask;
					float visibility=sampleDirectionalShadow(rel,dist,waterMask);
					visibility=smoothstep(0.03,0.97,visibility);

					float density=atmosphereDensity(wp,dist);
					if(uWater>0.5){
						density*=3.0;
						visibility*=mix(1.0,0.70,waterMask);
					}

					float extinction=density*mix(1.0,1.35,uWater);
					float segmentTransmittance=exp(-extinction*stepLength);
					float segmentScatter=1.0-segmentTransmittance;

					accumulated+=transmittance*segmentScatter*visibility;
					transmittance*=segmentTransmittance;
					meanVisibility+=visibility;
					meanWater+=waterMask;

					if(transmittance<0.015)break;
				}

				meanVisibility/=float(steps);
				meanWater/=float(steps);

				if(uDebug==3){
					f=vec4(vec3(meanVisibility),1.0);
					return;
				}
				if(uDebug==4){
					f=vec4(vec3(meanWater),1.0);
					return;
				}
				if(uDebug==5){
					f=vec4(vec3(1.0-transmittance),1.0);
					return;
				}

				float rainAttenuation=mix(1.0,0.52,saturate(uRainFactor));
				float phaseScale=uWater>0.5?2.4:4.8;
				float intensity=accumulated*phase*phaseScale*uStrength*rainAttenuation;

				if(uWater>0.5)intensity=min(intensity,0.20);
				else intensity=min(intensity,uMoon>0.5?0.16:0.42);

				f=vec4(max(uColor,vec3(0.0))*intensity,1.0);
			}
			""";
}
