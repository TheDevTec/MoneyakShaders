package com.moneyakshaders.render;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.moneyakshaders.MoneyakShaders;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

public final class FantasyClouds {
	private static final float CLOUD_BASE = 184f;
	private static final float CLOUD_TOP = 236f;
	/*
	 * The visual cloud pass intentionally follows Better Clouds' geometry model:
	 * a stable, noise-sampled field of instanced cloud voxels.  The old pass
	 * raymarched a thin density slab, which made a single threshold read as
	 * horizontal cut-off strips instead of cloud bodies.
	 */
	private static final float CLOUD_SPACING = 20f;
	private static final float CLOUD_SIZE_XZ = 38f;
	private static final float CLOUD_SIZE_Y = 4.5f;
	private static final float CLOUD_RENDER_DISTANCE = 1080f;
	private static final int CLOUD_GRID_RADIUS = (int)(CLOUD_RENDER_DISTANCE / CLOUD_SPACING);
	private static final long CLOUD_SEED = 0x4d6f6e6579616bL;
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

	private static final String MESH_VS = """
			#version 330 core
			layout(location=0) in vec3 aVertex;
			layout(location=1) in vec3 aNormal;
			layout(location=2) in vec3 aCloud;
			uniform mat4 uProjection;
			uniform mat4 uView;
			uniform vec3 uCamera;
			uniform float uTime;
			uniform float uSpeed;
			out vec3 vNormal;
			out vec3 vWorld;
			void main(){
				vec3 wind=vec3(uTime*uSpeed*1.85,0.0,uTime*uSpeed*0.54);
				vec3 world=aCloud+wind+aVertex*vec3(38.0,4.5,38.0);
				vNormal=aNormal;
				vWorld=world;
				// The captured terrain view matrix consumes camera-relative vertices.
				// Supplying absolute world coordinates here translated the entire cloud
				// field a second time, leaving it visible in only one sky quadrant.
				gl_Position=uProjection*uView*vec4(world-uCamera,1.0);
			}
			""";

	private static final String MESH_FS = """
			#version 330 core
			in vec3 vNormal;
			in vec3 vWorld;
			out vec4 f;
			uniform vec3 uCamera;
			uniform vec3 uLightDir;
			uniform vec3 uDirectColor;
			uniform vec3 uAmbientColor;
			uniform float uDirectStrength;
			uniform float uMoon;
			uniform float uRain;
			uniform float uSilver;
			void main(){
				float distanceToCloud=length(vWorld.xz-uCamera.xz);
				// Do not use a near-camera fade: it creates an artificial empty halo
				// that makes clouds appear to run away from the player.
				float fade=1.0;
				if(fade<0.01) discard;
				vec3 L=normalize(uLightDir);
				float sun=max(dot(normalize(vNormal),L),0.0);
				float rim=pow(max(dot(normalize(vWorld-uCamera),L),0.0),7.0)*uSilver;
				vec3 ambient=uAmbientColor*mix(0.42,0.68,1.0-uMoon);
				vec3 direct=uDirectColor*uDirectStrength*(0.20+sun*0.80);
				vec3 color=ambient+direct+uDirectColor*rim*0.35;
				color=mix(color,color*0.70,clamp(uRain,0.0,1.0));
				// Several thin layers may overlap; keep every individual slab airy
				// so the result stays bright and wispy instead of a solid dark island.
				f=vec4(max(color,vec3(0.0)),0.38*fade);
			}
			""";

	static final String SHARED_FIELD_GLSL = """
			const float CLOUD_BASE=184.0;
			const float CLOUD_TOP=236.0;

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
				// A compact three-deck profile: low fragments give the clouds a readable
				// underside, while the two upper decks build chunky Minecraft-scale volume
				// instead of one very tall, smoke-like slab.
				float bottom=smoothstep(0.035,0.17,h);
				float top=1.0-smoothstep(0.70,0.97,h);
				float deck=mix(0.72,1.0,step(0.34,h));
				return bottom*top*deck;
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
				// A cloudlet is a broad 105x105-block column with a variable 20–48 block
				// crown, not a stack of shallow horizontal tiles. This gives each bank a
				// visible underside, body and crest when viewed from the ground.
				vec2 column=floor(q.xz*0.0095);
				float blocks=cloudHash3(vec3(column,11.0));
				float bank=cloudNoise3(vec3(q.xz*0.0027,6.3));
				float h=clamp((p.y-CLOUD_BASE)/(CLOUD_TOP-CLOUD_BASE),0.0,1.0);
				float crown=0.36+cloudHash3(vec3(column,29.0))*0.56;
				float puffBottom=smoothstep(0.035,0.18,h);
				float puffTop=1.0-smoothstep(crown-0.17,crown,h);
				float threshold=0.750-uCloudCover*0.245-(weather-0.5)*0.16;
				float field=bank*0.80+blocks*0.20;
				// Narrow transition preserves a readable blocky silhouette rather than
				// the previous screen-wide fuzzy/dithered noise.
				float density=smoothstep(threshold,threshold+0.035,field);

				return density*profile*puffBottom*puffTop*uCloudDensity;
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

	private static int program, shadowProgram, vao, cloudVao, cloudMeshVbo, cloudInstanceVbo;
	private static int cloudInstances;
	private static int cloudGridX = Integer.MIN_VALUE, cloudGridZ = Integer.MIN_VALUE;
	private static float cloudGridCover = Float.NaN, cloudGridDensity = Float.NaN;
	private static int shadowFbo, shadowTex;
	private static int uProjection, uView, uCamWorld, uLightDir, uDirectColor, uAmbientColor;
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
		// The procedural mesh experiment did not maintain vanilla's world-space
		// traversal contract.  Until a replacement has that guarantee, the active
		// path is Minecraft's own physical cloud renderer.
		return false;
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
		if (program == 0 || cloudVao == 0) return;

		float cover = 0.48f;
		float density = 1.0f;
		float silver = 0.68f;
		float shadow = 0.68f;
		float speed = 0.50f;
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

		ensureCloudGeometry((float)camX, (float)camZ, timeSec, speed, cover, density);
		if (cloudInstances == 0) return;

		GlStateManager._glUseProgram(program);
		GL30.glBindVertexArray(cloudVao);
		GlStateManager._enableDepthTest();
		GlStateManager._depthFunc(GL11.GL_LEQUAL);
		GlStateManager._depthMask(false);
		GlStateManager._enableBlend();
		GlStateManager._blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
				GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
		GlStateManager._disableCull();

		try (MemoryStack stack = MemoryStack.stackPush()) {
			GL20.glUniformMatrix4fv(uProjection, false, proj.get(stack.mallocFloat(16)));
			GL20.glUniformMatrix4fv(uView, false, view.get(stack.mallocFloat(16)));
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

		GL31.glDrawArraysInstanced(GL11.GL_TRIANGLES, 0, 36, cloudInstances);

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

	/**
	 * Port of Better Clouds' important generation rule: sample a stable low-frequency
	 * cloud field on a grid, vary the column height by the sampled value, then add a
	 * short lower fill pass.  The grid is rebuilt only after crossing a 18-block cell
	 * (or when coverage/density changes), so clouds never swim with the camera.
	 */
	private static void ensureCloudGeometry(float camX, float camZ, float time, float speed, float cover, float density) {
		// The mesh vertex shader translates every instance by this wind vector.
		// Generate in the inverse-translated space so the *visible* cloud field,
		// not merely its unshifted source grid, remains centred on the player.
		float windX = time * speed * 1.85f;
		float windZ = time * speed * 0.54f;
		int gridX = floorDiv(camX - windX, CLOUD_SPACING);
		int gridZ = floorDiv(camZ - windZ, CLOUD_SPACING);
		if (gridX == cloudGridX && gridZ == cloudGridZ
				&& near(cover, cloudGridCover, 0.002f) && near(density, cloudGridDensity, 0.002f)) return;

		int maximum = (CLOUD_GRID_RADIUS * 2 + 1) * (CLOUD_GRID_RADIUS * 2 + 1) * 2;
		var points = MemoryUtil.memAllocFloat(maximum * 3);
		int count = 0;
		for (int x = -CLOUD_GRID_RADIUS; x <= CLOUD_GRID_RADIUS; x++) {
			for (int z = -CLOUD_GRID_RADIUS; z <= CLOUD_GRID_RADIUS; z++) {
				int cellX = gridX + x;
				int cellZ = gridZ + z;
				float worldX = cellX * CLOUD_SPACING;
				float worldZ = cellZ * CLOUD_SPACING;
				// Better Clouds samples a stable world grid rather than a camera-facing
				// billboard.  Keep that property, but deliberately bound each cloud bank:
				// an unbounded regional threshold was the cause of one giant colony on
				// one side of the sky and empty space everywhere else.
				int regionX = Math.floorDiv(cellX, 7);
				int regionZ = Math.floorDiv(cellZ, 7);
				if (hash01(regionX, regionZ, 211) > 0.23f + cover * 0.42f) continue;
				float centerX = regionX * 7f + 1.0f + hash01(regionX, regionZ, 223) * 5.0f;
				float centerZ = regionZ * 7f + 1.0f + hash01(regionX, regionZ, 227) * 5.0f;
				float radius = 2.25f + hash01(regionX, regionZ, 229) * 1.9f;
				float dx = cellX - centerX, dz = cellZ - centerZ;
				float angle = hash01(regionX, regionZ, 233) * (float)(Math.PI * 2.0);
				float longAxis = dx * (float)Math.cos(angle) + dz * (float)Math.sin(angle);
				float shortAxis = -dx * (float)Math.sin(angle) + dz * (float)Math.cos(angle);
				float stretch = 1.55f + hash01(regionX, regionZ, 239) * 1.55f;
				float radial = 1f - (float)Math.sqrt((longAxis / stretch) * (longAxis / stretch) + shortAxis * shortAxis) / radius;
				float detail = valueNoise(cellX * 0.21f, cellZ * 0.21f, 31) * 0.42f
						+ valueNoise(cellX * 0.065f, cellZ * 0.065f, 47) * 0.58f;
				float value = radial * (0.74f + detail * 0.38f);
				if (value <= 0f || hash01(cellX, cellZ, 101) > Math.min(1f, value + 0.12f * density)) continue;

				float height = value * value * 8f;
				float jitterX = (hash01(cellX, cellZ, 131) - 0.5f) * CLOUD_SPACING * 0.55f;
				float jitterZ = (hash01(cellX, cellZ, 151) - 0.5f) * CLOUD_SPACING * 0.55f;
				points.put(worldX + jitterX).put(CLOUD_BASE + height).put(worldZ + jitterZ);
				count++;
				// A second close layer produces the soft stacked streaks from the
				// reference without turning the cloud into a tall opaque column.
				if (value > 0.48f && count < maximum) {
					points.put(worldX + jitterX + CLOUD_SPACING * 0.28f).put(CLOUD_BASE + height + CLOUD_SIZE_Y * 0.78f).put(worldZ + jitterZ - CLOUD_SPACING * 0.18f);
					count++;
				}
			}
		}
		points.flip();
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, cloudInstanceVbo);
		GL15.glBufferData(GL15.GL_ARRAY_BUFFER, points, GL15.GL_DYNAMIC_DRAW);
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
		MemoryUtil.memFree(points);
		cloudInstances = count;
		cloudGridX = gridX;
		cloudGridZ = gridZ;
		cloudGridCover = cover;
		cloudGridDensity = density;
	}

	private static int floorDiv(float value, float divisor) {
		return (int)Math.floor(value / divisor);
	}

	private static float hash01(int x, int z, int salt) {
		long h = CLOUD_SEED;
		h ^= (long)x * 0x9E3779B97F4A7C15L;
		h ^= (long)z * 0xC2B2AE3D27D4EB4FL;
		h ^= (long)salt * 0x165667B19E3779F9L;
		h ^= h >>> 30;
		h *= 0xBF58476D1CE4E5B9L;
		h ^= h >>> 27;
		h *= 0x94D049BB133111EBL;
		h ^= h >>> 31;
		return (float)((h >>> 40) & 0xFFFFFFL) / 16777215f;
	}

	private static float valueNoise(float x, float z, int salt) {
		int ix = (int)Math.floor(x), iz = (int)Math.floor(z);
		float fx = x - ix, fz = z - iz;
		fx = fx * fx * (3f - 2f * fx);
		fz = fz * fz * (3f - 2f * fz);
		float a = hash01(ix, iz, salt), b = hash01(ix + 1, iz, salt);
		float c = hash01(ix, iz + 1, salt), d = hash01(ix + 1, iz + 1, salt);
		return mix(mix(a, b, fx), mix(c, d, fx), fz);
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
			program = GlShader.build(MESH_VS, MESH_FS);
			shadowProgram = GlShader.build(VS, SHADOW_FS);
			if (program == 0) return;

			vao = GL30.glGenVertexArrays();
			initCloudMesh();

			uProjection = GL20.glGetUniformLocation(program, "uProjection");
			uView = GL20.glGetUniformLocation(program, "uView");
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

			MoneyakShaders.LOGGER.info("[Plan C/GL] instanced Better-Clouds-style cloud programs linked (cloud={}, shadow={})",
					program, shadowProgram);
		} catch (Throwable t) {
			MoneyakShaders.LOGGER.warn("[Plan C/GL] volumetric cloud init failed", t);
			program = 0;
			shadowProgram = 0;
		}
	}

	private static void initCloudMesh() {
		// position xyz, outward normal xyz.  Kept as independent triangles so all
		// cube faces remain valid even where an adjacent sampled cell is absent.
		float[] cube = {
				-.5f,-.5f,-.5f, 0,-1,0,   .5f,-.5f, .5f, 0,-1,0,   .5f,-.5f,-.5f, 0,-1,0,
				-.5f,-.5f,-.5f, 0,-1,0,  -.5f,-.5f, .5f, 0,-1,0,   .5f,-.5f, .5f, 0,-1,0,
				-.5f,.5f,-.5f, 0,1,0,    .5f,.5f,-.5f, 0,1,0,    .5f,.5f, .5f, 0,1,0,
				-.5f,.5f,-.5f, 0,1,0,    .5f,.5f, .5f, 0,1,0,   -.5f,.5f, .5f, 0,1,0,
				-.5f,-.5f,-.5f, 0,0,-1,  .5f,-.5f,-.5f, 0,0,-1,  .5f,.5f,-.5f, 0,0,-1,
				-.5f,-.5f,-.5f, 0,0,-1,  .5f,.5f,-.5f, 0,0,-1, -.5f,.5f,-.5f, 0,0,-1,
				-.5f,-.5f,.5f, 0,0,1,    .5f,.5f,.5f, 0,0,1,    .5f,-.5f,.5f, 0,0,1,
				-.5f,-.5f,.5f, 0,0,1,   -.5f,.5f,.5f, 0,0,1,    .5f,.5f,.5f, 0,0,1,
				-.5f,-.5f,-.5f, -1,0,0, -.5f,.5f,-.5f, -1,0,0, -.5f,.5f,.5f, -1,0,0,
				-.5f,-.5f,-.5f, -1,0,0, -.5f,.5f,.5f, -1,0,0, -.5f,-.5f,.5f, -1,0,0,
				.5f,-.5f,-.5f, 1,0,0,   .5f,-.5f,.5f, 1,0,0,   .5f,.5f,.5f, 1,0,0,
				.5f,-.5f,-.5f, 1,0,0,   .5f,.5f,.5f, 1,0,0,    .5f,.5f,-.5f, 1,0,0
		};
		cloudVao = GL30.glGenVertexArrays();
		cloudMeshVbo = GL15.glGenBuffers();
		cloudInstanceVbo = GL15.glGenBuffers();
		GL30.glBindVertexArray(cloudVao);
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, cloudMeshVbo);
		var mesh = MemoryUtil.memAllocFloat(cube.length).put(cube).flip();
		GL15.glBufferData(GL15.GL_ARRAY_BUFFER, mesh, GL15.GL_STATIC_DRAW);
		MemoryUtil.memFree(mesh);
		GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 24, 0L);
		GL20.glEnableVertexAttribArray(0);
		GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, 24, 12L);
		GL20.glEnableVertexAttribArray(1);
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, cloudInstanceVbo);
		GL20.glVertexAttribPointer(2, 3, GL11.GL_FLOAT, false, 12, 0L);
		GL20.glEnableVertexAttribArray(2);
		GL33.glVertexAttribDivisor(2, 1);
		GL30.glBindVertexArray(0);
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
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
