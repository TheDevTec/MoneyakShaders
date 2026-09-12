package com.moneyakshaders.render;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.moneyakshaders.MoneyakShaders;
import com.moneyakshaders.MoneyakShadersConfig;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

public final class FantasyClouds {
	private static final float CLOUD_BASE = 184f;
	private static final float CLOUD_TOP = 310f;
	private static final int SHADOW_RES = 256;
	private static final float SHADOW_WORLD_SIZE = 2048f;
	private static final float SHADOW_CENTER_SNAP = 32f;
	private static final Matrix4f INV_PROJ = new Matrix4f(), INV_VIEW = new Matrix4f();

	private static final String VS = """
			#version 330 core
			out vec2 vUv;
			void main(){
				vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));
				vUv=p;
				gl_Position=vec4(p*2.0-1.0,1.0,1.0);
			}
			""";

	static final String SHARED_FIELD_GLSL = """
			const float CLOUD_BASE=184.0;
			const float CLOUD_TOP=310.0;

			float cloudHash3(vec3 p){
				p=fract(p*0.1031);
				p+=dot(p,p.yzx+33.33);
				return fract((p.x+p.y)*p.z);
			}

			float cloudNoise3(vec3 p){
				vec3 i=floor(p),q=fract(p);
				q=q*q*(3.0-2.0*q);

				float a=cloudHash3(i);
				float b=cloudHash3(i+vec3(1.0,0.0,0.0));
				float c=cloudHash3(i+vec3(0.0,1.0,0.0));
				float d=cloudHash3(i+vec3(1.0,1.0,0.0));
				float e=cloudHash3(i+vec3(0.0,0.0,1.0));
				float g=cloudHash3(i+vec3(1.0,0.0,1.0));
				float h=cloudHash3(i+vec3(0.0,1.0,1.0));
				float j=cloudHash3(i+vec3(1.0,1.0,1.0));

				return mix(
					mix(mix(a,b,q.x),mix(c,d,q.x),q.y),
					mix(mix(e,g,q.x),mix(h,j,q.x),q.y),
					q.z
				);
			}

			float cloudFbm(vec3 p){
				float value=0.0;
				float amplitude=0.56;

				for(int i=0;i<4;i++){
					value+=cloudNoise3(p)*amplitude;
					p=p*2.03+vec3(13.1,7.7,19.2);
					amplitude*=0.46;
				}

				return value;
			}

			float cloudHeightProfile(float y){
				float h=clamp((y-CLOUD_BASE)/(CLOUD_TOP-CLOUD_BASE),0.0,1.0);
				float bottom=smoothstep(0.015,0.145,h);
				float top=1.0-smoothstep(0.58,0.985,h);
				return bottom*top;
			}

			vec3 cloudWindOffset(){
				return vec3(
					uCloudTime*uCloudSpeed*1.85,
					0.0,
					uCloudTime*uCloudSpeed*0.54
				);
			}

			float cloudWeather(vec3 p){
				vec3 q=p+cloudWindOffset();
				float broad=cloudNoise3(vec3(q.xz*0.00165,4.7));
				float medium=cloudNoise3(vec3(q.xz*0.0031+vec2(17.3,-8.1),9.4));
				return broad*0.72+medium*0.28;
			}

			float cloudFieldDensity(vec3 p){
				float profile=cloudHeightProfile(p.y);
				if(profile<=0.0001)return 0.0;

				vec3 q=p+cloudWindOffset();
				float weather=cloudWeather(p);
				float body=cloudFbm(q*vec3(0.0042,0.0095,0.0042));
				float detail=cloudNoise3(q*vec3(0.015,0.029,0.015)+vec3(17.0,4.0,-9.0));

				float h=clamp((p.y-CLOUD_BASE)/(CLOUD_TOP-CLOUD_BASE),0.0,1.0);
				float threshold=0.625-uCloudCover*0.205-(weather-0.5)*0.135+h*0.038;
				float density=smoothstep(threshold,threshold+0.095,body*0.84+detail*0.16);

				return density*profile*uCloudDensity;
			}

			float cloudLightVisibility(vec3 p,vec3 L){
				float d=0.0;
				d+=cloudFieldDensity(p+L*12.0)*12.0;
				d+=cloudFieldDensity(p+L*28.0)*16.0;
				d+=cloudFieldDensity(p+L*52.0)*24.0;
				d+=cloudFieldDensity(p+L*84.0)*32.0;
				return exp(-d*0.022*uCloudSelfShadow);
			}
			""";

	private static final String FS = """
			#version 330 core
			in vec2 vUv;
			out vec4 f;

			uniform mat4 uInvProj;
			uniform mat4 uInvView;
			uniform vec3 uCamWorld;
			uniform vec3 uLightDir;
			uniform vec3 uDirectColor;
			uniform vec3 uAmbientColor;

			uniform float uCloudTime;
			uniform float uCloudCover;
			uniform float uCloudDensity;
			uniform float uCloudSpeed;
			uniform float uCloudSelfShadow;

			uniform float uDirectStrength;
			uniform float uMoon;
			uniform float uSilver;
			uniform float uRain;
			""" + SHARED_FIELD_GLSL + """

			const float PI=3.14159265359;

			float saturate(float x){
				return clamp(x,0.0,1.0);
			}

			float hash12(vec2 p){
				vec3 p3=fract(vec3(p.xyx)*0.1031);
				p3+=dot(p3,p3.yzx+33.33);
				return fract((p3.x+p3.y)*p3.z);
			}

			float henyeyGreenstein(float mu,float g){
				float gg=g*g;
				return (1.0-gg)/(4.0*PI*pow(max(1.0+gg-2.0*g*mu,0.001),1.5));
			}

			bool cloudSlab(vec3 ro,vec3 rd,out float a,out float b){
				if(abs(rd.y)<0.0001)return false;

				float t0=(CLOUD_BASE-ro.y)/rd.y;
				float t1=(CLOUD_TOP-ro.y)/rd.y;

				a=max(0.0,min(t0,t1));
				b=min(max(t0,t1),1000.0);

				return b>a;
			}

			void main(){
				vec2 ndc=vUv*2.0-1.0;
				vec4 q=uInvProj*vec4(ndc,1.0,1.0);
				q/=max(abs(q.w),0.00001);

				vec3 rd=normalize((uInvView*vec4(normalize(q.xyz),0.0)).xyz);
				float start,end;

				if(!cloudSlab(uCamWorld,rd,start,end))discard;

				const int STEPS=22;
				float totalLength=end-start;
				float stepLength=totalLength/float(STEPS);
				float jitter=hash12(gl_FragCoord.xy)-0.5;

				vec3 L=normalize(uLightDir);
				float mu=clamp(dot(rd,L),-1.0,1.0);
				float g=mix(0.58,0.30,uMoon);
				float phase=henyeyGreenstein(mu,g);
				float forward=saturate(phase*7.0);
				float sunHeight=saturate(L.y*2.5);

				vec3 acc=vec3(0.0);
				float trans=1.0;

				for(int i=0;i<STEPS;i++){
					if(trans<0.012)break;

					float t=start+(float(i)+0.5+jitter*0.60)*stepLength;
					t=clamp(t,start,end);

					vec3 p=uCamWorld+rd*t;
					float density=cloudFieldDensity(p);
					if(density<0.004)continue;

					float visibility=cloudLightVisibility(p,L);
					float edge=exp(-density*3.0);

					float silver=forward*edge*visibility*uSilver;
					float height=clamp((p.y-CLOUD_BASE)/(CLOUD_TOP-CLOUD_BASE),0.0,1.0);

					vec3 ambient=uAmbientColor*mix(0.46,0.72,height);
					vec3 direct=uDirectColor*uDirectStrength*visibility;
					direct*=mix(0.58,1.05,sunHeight);

					vec3 scatter=ambient+direct*(0.58+forward*0.85);
					scatter+=uDirectColor*silver*1.45*uDirectStrength;

					float extinction=density*0.024;
					extinction*=mix(1.0,1.16,uRain);

					float segmentTrans=exp(-extinction*stepLength);
					float alpha=1.0-segmentTrans;
					alpha*=1.0-smoothstep(760.0,1000.0,t);

					acc+=trans*scatter*alpha;
					trans*=segmentTrans;
				}

				float alpha=1.0-trans;
				if(alpha<0.002)discard;

				vec3 color=acc/max(alpha,0.001);
				color=max(color,vec3(0.0));

				f=vec4(color,alpha);
			}
			""";

	private static final String SHADOW_FS = """
			#version 330 core
			in vec2 vUv;
			out vec4 f;

			uniform vec2 uShadowCenter;
			uniform vec3 uLightDir;

			uniform float uCloudTime;
			uniform float uCloudCover;
			uniform float uCloudDensity;
			uniform float uCloudSpeed;
			uniform float uCloudSelfShadow;
			uniform float uCloudShadowStrength;
			uniform float uMoon;
			""" + SHARED_FIELD_GLSL + """

			void main(){
				vec3 L=normalize(uLightDir);

				if(L.y<=0.035||uCloudCover<=0.001||uCloudShadowStrength<=0.001){
					f=vec4(1.0);
					return;
				}

				vec2 baseXZ=uShadowCenter+(vUv-0.5)*2048.0;
				vec3 start=vec3(baseXZ,CLOUD_BASE+0.5).xzy;

				float pathLength=(CLOUD_TOP-CLOUD_BASE)/max(L.y,0.08);
				pathLength=min(pathLength,620.0);

				const int STEPS=12;
				float stepLength=pathLength/float(STEPS);
				float opticalDepth=0.0;

				for(int i=0;i<STEPS;i++){
					float t=(float(i)+0.5)*stepLength;
					vec3 p=start+L*t;
					if(p.y>CLOUD_TOP)break;
					opticalDepth+=cloudFieldDensity(p)*stepLength;
				}

				float visibility=exp(-opticalDepth*0.019);
				float strength=uCloudShadowStrength*mix(1.0,0.52,uMoon);
				visibility=mix(1.0,visibility,clamp(strength,0.0,1.0));

				f=vec4(visibility,visibility,visibility,1.0);
			}
			""";

	private static int program, shadowProgram, vao;
	private static int shadowFbo, shadowTex;
	private static int uInvProj, uInvView, uCamWorld, uLightDir, uDirectColor, uAmbientColor;
	private static int uCloudTime, uCloudCover, uCloudDensity, uCloudSpeed, uCloudSelfShadow;
	private static int uDirectStrength, uMoon, uSilver, uRain;
	private static int suShadowCenter, suLightDir, suCloudTime, suCloudCover, suCloudDensity, suCloudSpeed;
	private static int suCloudSelfShadow, suCloudShadowStrength, suMoon;
	private static boolean init, shadowReady;
	private static float shadowCenterX, shadowCenterZ;
	private static float lastShadowTime = Float.NEGATIVE_INFINITY;
	private static float lastShadowLightX = Float.NaN, lastShadowLightY = Float.NaN, lastShadowLightZ = Float.NaN;
	private static float lastShadowCover = Float.NaN, lastShadowDensity = Float.NaN, lastShadowSpeed = Float.NaN;
	private static float lastShadowStrength = Float.NaN, lastShadowMoon = Float.NaN;
	private static long shadowRevision;

	private FantasyClouds() {}

	public static boolean isSupported() {
		return true;
	}

	public static int shadowTexture() {
		return shadowReady ? shadowTex : 0;
	}

	public static boolean shadowReady() {
		return shadowReady && shadowTex != 0;
	}

	public static float shadowCenterX() {
		return shadowCenterX;
	}

	public static float shadowCenterZ() {
		return shadowCenterZ;
	}

	public static float shadowWorldSize() {
		return SHADOW_WORLD_SIZE;
	}

	public static float shadowBaseY() {
		return CLOUD_BASE;
	}

	public static long shadowRevision() {
		return shadowRevision;
	}

	public static void render(Matrix4f proj, Matrix4f view, double camX, double camY, double camZ,
			float timeSec, SceneLightingSnapshot lighting) {
		if (!init) init();
		if (program == 0) return;

		MoneyakShadersConfig cfg = MoneyakShadersConfig.get();
		float cover = clamp01(cfg.cloudCoverage / 100f);
		float density = 0.55f + clamp01(cfg.cloudDensity / 100f) * 0.85f;
		float silver = clamp01(cfg.cloudSilverLining / 100f);
		float shadow = clamp01(cfg.cloudShadowStrength / 100f);
		float speed = 0.18f + clamp01(cfg.cloudSpeed / 100f) * 0.82f;
		float moonFactor = lighting.moonLighting ? 1f : 0f;

		updateShadowMap(camX, camZ, timeSec, cover, density, speed, shadow,
				lighting.direction.x, lighting.direction.y, lighting.direction.z, moonFactor);

		int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
		int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
		boolean prevDepth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
		boolean prevBlend = GL11.glIsEnabled(GL11.GL_BLEND);
		boolean prevCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
		int prevDepthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
		boolean prevDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
		int srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
		int dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
		int srcA = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
		int dstA = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);

		GlStateManager._glUseProgram(program);
		GL30.glBindVertexArray(vao);
		GlStateManager._enableDepthTest();
		GlStateManager._depthFunc(GL11.GL_LEQUAL);
		GlStateManager._depthMask(false);
		GlStateManager._enableBlend();
		GlStateManager._blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
				GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
		GlStateManager._disableCull();

		INV_PROJ.set(proj).invert();
		INV_VIEW.set(view).invert();

		try (MemoryStack stack = MemoryStack.stackPush()) {
			GL20.glUniformMatrix4fv(uInvProj, false, INV_PROJ.get(stack.mallocFloat(16)));
			GL20.glUniformMatrix4fv(uInvView, false, INV_VIEW.get(stack.mallocFloat(16)));
		}

		float directStrength = clamp01(lighting.directStrength);

		GL20.glUniform3f(uCamWorld, (float)camX, (float)camY, (float)camZ);
		GL20.glUniform3f(uLightDir, lighting.direction.x, lighting.direction.y, lighting.direction.z);
		GL20.glUniform3f(uDirectColor, lighting.directColor.x, lighting.directColor.y, lighting.directColor.z);
		GL20.glUniform3f(uAmbientColor, lighting.skyAmbientColor.x, lighting.skyAmbientColor.y, lighting.skyAmbientColor.z);
		GL20.glUniform1f(uCloudTime, timeSec);
		GL20.glUniform1f(uCloudCover, cover);
		GL20.glUniform1f(uCloudDensity, density);
		GL20.glUniform1f(uCloudSpeed, speed);
		GL20.glUniform1f(uCloudSelfShadow, 0.85f + shadow * 1.45f);
		GL20.glUniform1f(uDirectStrength, directStrength);
		GL20.glUniform1f(uMoon, moonFactor);
		GL20.glUniform1f(uSilver, silver);
		GL20.glUniform1f(uRain, lighting.rainFactor);

		GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);

		GlStateManager._blendFuncSeparate(srcRgb, dstRgb, srcA, dstA);
		GlStateManager._depthMask(prevDepthMask);
		GlStateManager._depthFunc(prevDepthFunc);

		if (prevDepth) GlStateManager._enableDepthTest(); else GlStateManager._disableDepthTest();
		if (prevBlend) GlStateManager._enableBlend(); else GlStateManager._disableBlend();
		if (prevCull) GlStateManager._enableCull(); else GlStateManager._disableCull();

		GlStateManager._glUseProgram(prevProgram);
		GL30.glBindVertexArray(prevVao);
	}

	private static void updateShadowMap(double camX, double camZ, float time, float cover, float density,
			float speed, float strength, float lx, float ly, float lz, float moon) {
		if (shadowProgram == 0) return;

		float centerX = snap((float)camX, SHADOW_CENTER_SNAP);
		float centerZ = snap((float)camZ, SHADOW_CENTER_SNAP);
		boolean moved = centerX != shadowCenterX || centerZ != shadowCenterZ;
		boolean lightChanged = !near(lx, lastShadowLightX, 0.004f)
				|| !near(ly, lastShadowLightY, 0.004f)
				|| !near(lz, lastShadowLightZ, 0.004f);
		boolean settingsChanged = !near(cover, lastShadowCover, 0.002f)
				|| !near(density, lastShadowDensity, 0.002f)
				|| !near(speed, lastShadowSpeed, 0.002f)
				|| !near(strength, lastShadowStrength, 0.002f)
				|| !near(moon, lastShadowMoon, 0.002f);
		boolean animated = time-lastShadowTime >= 0.075f;

		if (shadowReady && !moved && !lightChanged && !settingsChanged && !animated) return;
		ensureShadowTarget();
		if (shadowFbo == 0 || shadowTex == 0) return;

		int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
		int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
		int prevFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
		int[] viewport = new int[4];
		GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);

		boolean prevDepth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
		boolean prevBlend = GL11.glIsEnabled(GL11.GL_BLEND);
		boolean prevCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
		boolean prevDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);

		GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, shadowFbo);
		GlStateManager._viewport(0, 0, SHADOW_RES, SHADOW_RES);
		GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
		GlStateManager._disableDepthTest();
		GlStateManager._depthMask(false);
		GlStateManager._disableBlend();
		GlStateManager._disableCull();
		GlStateManager._glUseProgram(shadowProgram);
		GL30.glBindVertexArray(vao);

		GL20.glUniform2f(suShadowCenter, centerX, centerZ);
		GL20.glUniform3f(suLightDir, lx, ly, lz);
		GL20.glUniform1f(suCloudTime, time);
		GL20.glUniform1f(suCloudCover, cover);
		GL20.glUniform1f(suCloudDensity, density);
		GL20.glUniform1f(suCloudSpeed, speed);
		GL20.glUniform1f(suCloudSelfShadow, 1f);
		GL20.glUniform1f(suCloudShadowStrength, strength);
		GL20.glUniform1f(suMoon, moon);

		GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);

		GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
		GlStateManager._viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
		GlStateManager._depthMask(prevDepthMask);

		if (prevDepth) GlStateManager._enableDepthTest(); else GlStateManager._disableDepthTest();
		if (prevBlend) GlStateManager._enableBlend(); else GlStateManager._disableBlend();
		if (prevCull) GlStateManager._enableCull(); else GlStateManager._disableCull();

		GlStateManager._glUseProgram(prevProgram);
		GL30.glBindVertexArray(prevVao);

		shadowCenterX = centerX;
		shadowCenterZ = centerZ;
		lastShadowTime = time;
		lastShadowLightX = lx;
		lastShadowLightY = ly;
		lastShadowLightZ = lz;
		lastShadowCover = cover;
		lastShadowDensity = density;
		lastShadowSpeed = speed;
		lastShadowStrength = strength;
		lastShadowMoon = moon;
		shadowReady = true;
		shadowRevision++;
	}

	private static void ensureShadowTarget() {
		if (shadowFbo != 0 && shadowTex != 0) return;

		int prevActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
		GlStateManager._activeTexture(GL13.GL_TEXTURE0);
		int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
		int prevFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);

		if (shadowTex == 0) shadowTex = GL11.glGenTextures();
		GlStateManager._bindTexture(shadowTex);
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8, SHADOW_RES, SHADOW_RES, 0,
				GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, 0L);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

		if (shadowFbo == 0) shadowFbo = GL30.glGenFramebuffers();
		GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, shadowFbo);
		GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
				GL11.GL_TEXTURE_2D, shadowTex, 0);
		GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);

		int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
		if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
			MoneyakShaders.LOGGER.warn("[Plan C/GL] cloud shadow framebuffer incomplete: 0x{}",
					Integer.toHexString(status));
			GL30.glDeleteFramebuffers(shadowFbo);
			GL11.glDeleteTextures(shadowTex);
			shadowFbo = 0;
			shadowTex = 0;
		}

		GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
		GlStateManager._bindTexture(prevTex);
		GlStateManager._activeTexture(prevActive);
	}

	private static void init() {
		init = true;

		try {
			program = GlShader.build(VS, FS);
			shadowProgram = GlShader.build(VS, SHADOW_FS);
			if (program == 0) return;

			vao = GL30.glGenVertexArrays();

			uInvProj = GL20.glGetUniformLocation(program, "uInvProj");
			uInvView = GL20.glGetUniformLocation(program, "uInvView");
			uCamWorld = GL20.glGetUniformLocation(program, "uCamWorld");
			uLightDir = GL20.glGetUniformLocation(program, "uLightDir");
			uDirectColor = GL20.glGetUniformLocation(program, "uDirectColor");
			uAmbientColor = GL20.glGetUniformLocation(program, "uAmbientColor");
			uCloudTime = GL20.glGetUniformLocation(program, "uCloudTime");
			uCloudCover = GL20.glGetUniformLocation(program, "uCloudCover");
			uCloudDensity = GL20.glGetUniformLocation(program, "uCloudDensity");
			uCloudSpeed = GL20.glGetUniformLocation(program, "uCloudSpeed");
			uCloudSelfShadow = GL20.glGetUniformLocation(program, "uCloudSelfShadow");
			uDirectStrength = GL20.glGetUniformLocation(program, "uDirectStrength");
			uMoon = GL20.glGetUniformLocation(program, "uMoon");
			uSilver = GL20.glGetUniformLocation(program, "uSilver");
			uRain = GL20.glGetUniformLocation(program, "uRain");

			if (shadowProgram != 0) {
				suShadowCenter = GL20.glGetUniformLocation(shadowProgram, "uShadowCenter");
				suLightDir = GL20.glGetUniformLocation(shadowProgram, "uLightDir");
				suCloudTime = GL20.glGetUniformLocation(shadowProgram, "uCloudTime");
				suCloudCover = GL20.glGetUniformLocation(shadowProgram, "uCloudCover");
				suCloudDensity = GL20.glGetUniformLocation(shadowProgram, "uCloudDensity");
				suCloudSpeed = GL20.glGetUniformLocation(shadowProgram, "uCloudSpeed");
				suCloudSelfShadow = GL20.glGetUniformLocation(shadowProgram, "uCloudSelfShadow");
				suCloudShadowStrength = GL20.glGetUniformLocation(shadowProgram, "uCloudShadowStrength");
				suMoon = GL20.glGetUniformLocation(shadowProgram, "uMoon");
			}

			MoneyakShaders.LOGGER.info("[Plan C/GL] volumetric cloud programs linked (cloud={}, shadow={})",
					program, shadowProgram);
		} catch (Throwable t) {
			MoneyakShaders.LOGGER.warn("[Plan C/GL] volumetric cloud init failed", t);
			program = 0;
			shadowProgram = 0;
		}
	}

	private static boolean near(float a, float b, float epsilon) {
		return Float.isFinite(a) && Float.isFinite(b) && Math.abs(a-b) <= epsilon;
	}

	private static float snap(float value, float grid) {
		return (float)Math.floor(value/grid)*grid;
	}

	private static float mix(float a, float b, float t) {
		return a+(b-a)*t;
	}

	private static float clamp01(float value) {
		return Math.max(0f, Math.min(1f, value));
	}
}
