package com.moneyakshaders.render;

import java.nio.ByteBuffer;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.system.MemoryStack;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.textures.GpuTexture;
import com.moneyakshaders.MoneyakShaders;
import com.moneyakshaders.MoneyakShadersConfig;
import com.moneyakshaders.client.UnderwaterFogSync;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.texture.GlTexture;

public final class PostProcess {
	private static final int BLOOM_LEVELS = 4;
	static final String VS = """
			#version 330 core
			out vec2 vUv;
			void main(){
				vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));
				vUv=p;
				gl_Position=vec4(p*2.0-1.0,0.0,1.0);
			}
			""";

	private static final String COPY_FS = """
			#version 330 core
			in vec2 vUv;
			out vec4 f;
			uniform sampler2D uTex;
			void main(){
				f=vec4(max(texture(uTex,vUv).rgb,vec3(0.0)),1.0);
			}
			""";

	private static final String AERIAL_FS = """
			#version 330 core
			in vec2 vUv;
			out vec4 f;

			uniform sampler2D uScene;
			uniform sampler2D uDepth;
			uniform mat4 uInvProj;
			uniform vec3 uFogColor;
			uniform vec3 uUnderwaterColor;
			uniform float uAtmosphereDensity;
			uniform float uAtmosphereHorizon;
			uniform float uUnderwater;
			uniform float uPost;

			float saturate(float v){
				return clamp(v,0.0,1.0);
			}

			vec3 viewFromDepth(float depth){
				vec4 p=uInvProj*vec4(vUv*2.0-1.0,depth*2.0-1.0,1.0);
				return p.xyz/max(abs(p.w),0.000001);
			}

			void main(){
				vec3 col=max(texture(uScene,vUv).rgb,vec3(0.0));
				float depth=texture(uDepth,vUv).r;

				if(depth<0.99995){
					vec3 viewPos=viewFromDepth(depth);
					float dist=length(viewPos);

					if(uPost>0.5&&uAtmosphereDensity>0.0001){
						vec3 ray=normalize(viewPos);
						float horizon=1.0-smoothstep(0.05,0.62,abs(ray.y));
						float density=uAtmosphereDensity*(1.0+horizon*uAtmosphereHorizon*0.85);
						float haze=1.0-exp(-dist*density*0.0052);
						haze*=smoothstep(16.0,150.0,dist);
						col=mix(col,uFogColor,saturate(haze*0.88));
					}

					if(uUnderwater>0.5){
						// Water stays clear close to the camera, then loses contrast and detail
						// with travelled distance instead of applying a uniform cyan screen tint.
						float travel=max(dist-3.0,0.0);
						vec3 extinction=exp(-travel*vec3(0.075,0.032,0.018));
						float turbidity=1.0-exp(-travel*0.095);
						vec3 inscatter=uUnderwaterColor*(0.72+0.28*(1.0-exp(-travel*0.20)));
						col=col*extinction+inscatter*(1.0-extinction)*0.90;
						col=mix(col,inscatter,smoothstep(13.0,38.0,dist)*0.62);
						col=mix(col,vec3(dot(col,vec3(0.2126,0.7152,0.0722))),turbidity*0.14);
					}
				}

				f=vec4(max(col,vec3(0.0)),1.0);
			}
			""";

	private static final String BLOOM_PREFILTER_FS = """
			#version 330 core
			in vec2 vUv;
			out vec4 f;

			uniform sampler2D uScene;
			uniform sampler2D uShafts;
			uniform float uShaftMix;
			uniform float uThreshold;
			uniform float uKnee;

			void main(){
				vec3 c=max(texture(uScene,vUv).rgb,vec3(0.0));
				c+=max(texture(uShafts,vUv).rgb,vec3(0.0))*uShaftMix;

				float b=max(c.r,max(c.g,c.b));
				float soft=clamp(b-uThreshold+uKnee,0.0,2.0*uKnee);
				soft=soft*soft/(4.0*uKnee+0.00001);

				float contribution=max(b-uThreshold,soft)/max(b,0.00001);
				f=vec4(c*contribution,1.0);
			}
			""";

	private static final String BLOOM_DOWN_FS = """
			#version 330 core
			in vec2 vUv;
			out vec4 f;

			uniform sampler2D uTex;
			uniform vec2 uTexel;

			void main(){
				vec2 t=uTexel;

				vec3 a=texture(uTex,vUv+vec2(-2.0,-2.0)*t).rgb;
				vec3 b=texture(uTex,vUv+vec2( 0.0,-2.0)*t).rgb;
				vec3 c=texture(uTex,vUv+vec2( 2.0,-2.0)*t).rgb;
				vec3 d=texture(uTex,vUv+vec2(-2.0, 0.0)*t).rgb;
				vec3 e=texture(uTex,vUv).rgb;
				vec3 g=texture(uTex,vUv+vec2( 2.0, 0.0)*t).rgb;
				vec3 h=texture(uTex,vUv+vec2(-2.0, 2.0)*t).rgb;
				vec3 i=texture(uTex,vUv+vec2( 0.0, 2.0)*t).rgb;
				vec3 j=texture(uTex,vUv+vec2( 2.0, 2.0)*t).rgb;

				vec3 k=texture(uTex,vUv+vec2(-1.0,-1.0)*t).rgb;
				vec3 l=texture(uTex,vUv+vec2( 1.0,-1.0)*t).rgb;
				vec3 m=texture(uTex,vUv+vec2(-1.0, 1.0)*t).rgb;
				vec3 n=texture(uTex,vUv+vec2( 1.0, 1.0)*t).rgb;

				vec3 result=e*0.125;
				result+=(a+c+h+j)*0.03125;
				result+=(b+d+g+i)*0.0625;
				result+=(k+l+m+n)*0.09375;

				f=vec4(max(result,vec3(0.0)),1.0);
			}
			""";

	private static final String BLOOM_UP_FS = """
			#version 330 core
			in vec2 vUv;
			out vec4 f;

			uniform sampler2D uTex;
			uniform vec2 uTexel;

			void main(){
				vec2 t=uTexel;

				vec3 c=texture(uTex,vUv).rgb*4.0;
				c+=texture(uTex,vUv+vec2(-1.0,0.0)*t).rgb*2.0;
				c+=texture(uTex,vUv+vec2( 1.0,0.0)*t).rgb*2.0;
				c+=texture(uTex,vUv+vec2(0.0,-1.0)*t).rgb*2.0;
				c+=texture(uTex,vUv+vec2(0.0, 1.0)*t).rgb*2.0;
				c+=texture(uTex,vUv+vec2(-1.0,-1.0)*t).rgb;
				c+=texture(uTex,vUv+vec2( 1.0,-1.0)*t).rgb;
				c+=texture(uTex,vUv+vec2(-1.0, 1.0)*t).rgb;
				c+=texture(uTex,vUv+vec2( 1.0, 1.0)*t).rgb;

				f=vec4(c/16.0,1.0);
			}
			""";

	static final String COMPOSITE_FS = """
			#version 330 core
			in vec2 vUv;
			out vec4 f;

			uniform sampler2D uScene;
			uniform sampler2D uBloom;
			uniform sampler2D uDepth;
			uniform sampler2D uShafts;

			uniform vec2 uTexel;
			uniform float uBloomStrength;
			uniform float uExposure;
			uniform float uSaturation;
			uniform float uVignette;
			uniform float uOutline;
			uniform float uFxaa;
			uniform float uPost;
			uniform float uShaftMix;
			uniform float uNear;
			uniform float uFar;
			uniform int uDebug;

			float saturate(float v){
				return clamp(v,0.0,1.0);
			}

			float max3(vec3 v){
				return max(v.x,max(v.y,v.z));
			}

			float linearDepth(float d){
				return uNear*uFar/(uFar-d*(uFar-uNear));
			}

			vec3 aces(vec3 x){
				x=max(x,vec3(0.0));
				vec3 a=x*(2.51*x+0.03);
				vec3 b=x*(2.43*x+0.59)+0.14;
				return clamp(a/b,0.0,1.0);
			}

			vec3 colorGrade(vec3 col){
				float l=dot(col,vec3(0.2126,0.7152,0.0722));

				float shadow=1.0-smoothstep(0.08,0.34,l);
				float highlight=smoothstep(0.58,0.94,l);

				col*=mix(vec3(0.955,0.975,1.045),vec3(1.0),1.0-shadow*0.30);
				col*=mix(vec3(1.0),vec3(1.030,1.006,0.972),highlight*0.42);

				l=dot(col,vec3(0.2126,0.7152,0.0722));
				return max(mix(vec3(l),col,clamp(uSaturation,0.70,1.30)),vec3(0.0));
			}

			vec3 processColor(vec2 uv){
				vec3 col=max(texture(uScene,uv).rgb,vec3(0.0));
				col+=max(texture(uShafts,uv).rgb,vec3(0.0))*uShaftMix;

				if(uPost>0.5){
					col+=max(texture(uBloom,uv).rgb,vec3(0.0))*uBloomStrength;
					col=aces(col*uExposure);
					col=colorGrade(col);

					vec2 q=uv*2.0-1.0;
					float vignette=smoothstep(0.28,1.18,dot(q,q));
					col*=1.0-uVignette*vignette*0.48;
				}

				return max(col,vec3(0.0));
			}

			float depthWeight(float centerDepth,float neighborDepth){
				if(centerDepth>=0.99995&&neighborDepth>=0.99995)return 1.0;
				if(centerDepth>=0.99995||neighborDepth>=0.99995)return 0.0;

				float a=linearDepth(centerDepth);
				float b=linearDepth(neighborDepth);
				float scale=max(0.04,a*0.018);

				return exp(-abs(a-b)/scale);
			}

			vec3 depthAwareAA(vec2 uv,vec3 center){
				float centerDepth=texture(uDepth,uv).r;

				vec2 x=vec2(uTexel.x,0.0);
				vec2 y=vec2(0.0,uTexel.y);

				vec3 left=processColor(uv-x);
				vec3 right=processColor(uv+x);
				vec3 up=processColor(uv-y);
				vec3 down=processColor(uv+y);

				float lc=dot(center,vec3(0.299,0.587,0.114));
				float ll=dot(left,vec3(0.299,0.587,0.114));
				float lr=dot(right,vec3(0.299,0.587,0.114));
				float lu=dot(up,vec3(0.299,0.587,0.114));
				float ld=dot(down,vec3(0.299,0.587,0.114));

				float lmin=min(lc,min(min(ll,lr),min(lu,ld)));
				float lmax=max(lc,max(max(ll,lr),max(lu,ld)));
				float contrast=lmax-lmin;

				if(contrast<0.035)return center;

				float wl=depthWeight(centerDepth,texture(uDepth,uv-x).r);
				float wr=depthWeight(centerDepth,texture(uDepth,uv+x).r);
				float wu=depthWeight(centerDepth,texture(uDepth,uv-y).r);
				float wd=depthWeight(centerDepth,texture(uDepth,uv+y).r);

				float total=2.0+wl+wr+wu+wd;
				vec3 filtered=(center*2.0+left*wl+right*wr+up*wu+down*wd)/total;

				float edge=smoothstep(0.035,0.16,contrast);
				return mix(center,filtered,edge*0.56);
			}

			void main(){
				float rawDepth=texture(uDepth,vUv).r;

				if(uDebug==2){
					float z=rawDepth>=0.99995?uFar:linearDepth(rawDepth);
					f=vec4(vec3(1.0-exp(-z/96.0)),1.0);
					return;
				}

				if(uDebug==1||uDebug==3||uDebug==4||uDebug==5){
					vec3 debug=max(texture(uShafts,vUv).rgb,vec3(0.0));
					f=vec4(pow(clamp(debug,0.0,1.0),vec3(0.72)),1.0);
					return;
				}

				vec3 col=processColor(vUv);
				if(uPost>0.5&&uFxaa>0.5)col=depthAwareAA(vUv,col);

				if(uPost>0.5&&uOutline>0.0001&&rawDepth<0.99995){
					float dc=linearDepth(rawDepth);
					float dl=linearDepth(texture(uDepth,vUv-vec2(uTexel.x,0.0)).r);
					float dr=linearDepth(texture(uDepth,vUv+vec2(uTexel.x,0.0)).r);
					float du=linearDepth(texture(uDepth,vUv-vec2(0.0,uTexel.y)).r);
					float dd=linearDepth(texture(uDepth,vUv+vec2(0.0,uTexel.y)).r);

					float edge=(abs(dc-dl)+abs(dc-dr)+abs(dc-du)+abs(dc-dd))/max(dc,1.0);
					col*=1.0-uOutline*smoothstep(0.020,0.068,edge);
				}

				f=vec4(max(col,vec3(0.0)),1.0);
			}
			""";

	private static final String SHAFTS_FS = AtmosphericShafts.FS;

	private static boolean initialised;
	private static int copyProg, aerialProg, bloomPrefilterProg, bloomDownProg, bloomUpProg, compositeProg, shaftsProg;
	private static int dummyVao, ioFbo, ioAttachedColor;
	private static int sceneFboA, sceneFboB, sceneTexA, sceneTexB;
	private static final int[] bloomFbo = new int[BLOOM_LEVELS];
	private static final int[] bloomTex = new int[BLOOM_LEVELS];
	private static final int[] bloomW = new int[BLOOM_LEVELS];
	private static final int[] bloomH = new int[BLOOM_LEVELS];
	private static int shaftsFbo, shaftsTex, whiteTex;
	private static int tw, th;

	private static int uCopyTex;
	private static int aScene, aDepth, aInvProj, aFogColor, aUnderwaterColor, aAtmosphereDensity, aAtmosphereHorizon, aUnderwater, aPost;
	private static int bpScene, bpShafts, bpShaftMix, bpThreshold, bpKnee;
	private static int bdTex, bdTexel, buTex, buTexel;
	private static int cScene, cBloom, cDepth, cShafts, cTexel, cBloomStrength, cExposure, cSaturation;
	private static int cVignette, cOutline, cFxaa, cPost, cShaftMix, cNear, cFar, cDebug;

	private static int sDepth;
	private static final int[] sShadow = new int[3];
	private static final int[] sWaterShadow = new int[3];
	private static final int[] sLightMVP = new int[3];
	private static final int[] sShadowOffset = new int[3];
	private static final int[] sCascadeEnd = new int[3];
	private static final int[] sCascadeBlend = new int[2];
	private static int sInvProj, sInvView, sCameraWorld, sLightDir, sMoon, sColor, sStrength, sWater;
	private static int sTime, sNear, sFar, sDensity, sRainFactor, sDebug;

	private static final Matrix4f SHADOW_NEAR = new Matrix4f();
	private static final Matrix4f SHADOW_MID = new Matrix4f();
	private static final Matrix4f SHADOW_FAR = new Matrix4f();
	private static final Matrix4f CAMERA_PROJ = new Matrix4f();
	private static final Matrix4f CAMERA_VIEW = new Matrix4f();
	private static final Matrix4f CAMERA_INV_PROJ = new Matrix4f();
	private static final Matrix4f CAMERA_INV_VIEW = new Matrix4f();
	private static final Vector3f SHADOW_OFF_NEAR = new Vector3f();
	private static final Vector3f SHADOW_OFF_MID = new Vector3f();
	private static final Vector3f SHADOW_OFF_FAR = new Vector3f();
	private static final float[] CELESTIAL = new float[8];
	private static final float[] CASCADE = new float[5];
	private static final float[] CLEAR_ZERO = { 0f, 0f, 0f, 0f };
	private static final int[] VIEWPORT = new int[4];
	private static final int[] PREV_TEX = new int[7];
	private static final int[] PREV_SAMPLER = new int[7];

	private PostProcess() {}

	public static void run(MinecraftClient client) {
		MoneyakShadersConfig cfg = MoneyakShadersConfig.get();
		boolean underwater = client.gameRenderer != null
				&& client.gameRenderer.getCamera().getSubmersionType() == net.minecraft.block.enums.CameraSubmersionType.WATER;
		boolean post = cfg.postProcessing;
		boolean debug = cfg.postDebugView != 0;
		if (!post && !underwater && !debug) return;

		Framebuffer fb = client.getFramebuffer();
		if (fb == null || fb.textureWidth <= 0 || fb.textureHeight <= 0) return;

		GpuTexture colorAttachment = fb.getColorAttachment();
		if (!(colorAttachment instanceof GlTexture color)) return;
		int mainColor = color.getGlId();
		if (mainColor <= 0) return;

		int depth = fb.getDepthAttachment() instanceof GlTexture d ? d.getGlId() : 0;
		if (depth <= 0) return;

		if (!initialised) {
			initialised = true;
			init();
		}
		if (compositeProg == 0) return;

		int w = fb.textureWidth, h = fb.textureHeight;
		ensureTargets(w, h);

		int prevFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
		int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
		int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
		int prevActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
		GL11.glGetIntegerv(GL11.GL_VIEWPORT, VIEWPORT);

		boolean prevDepth = RenderGlState.depthTest();
		boolean prevBlend = RenderGlState.blendEnabled();
		boolean prevCull = RenderGlState.cullEnabled();
		boolean prevDepthMask = RenderGlState.depthMask();
		int prevDepthFunc = RenderGlState.depthFunc();
		int prevBlendSrcRgb = RenderGlState.blendSrcRgb();
		int prevBlendDstRgb = RenderGlState.blendDstRgb();
		int prevBlendSrcAlpha = RenderGlState.blendSrcAlpha();
		int prevBlendDstAlpha = RenderGlState.blendDstAlpha();
		int prevCullFace = GL11.glGetInteger(GL11.GL_CULL_FACE_MODE);
		int prevFrontFace = GL11.glGetInteger(GL11.GL_FRONT_FACE);
		int prevColorMask = colorMaskBits();

		for (int i = 0; i < PREV_TEX.length; i++) {
			GlStateManager._activeTexture(GL13.GL_TEXTURE0 + i);
			PREV_TEX[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
			PREV_SAMPLER[i] = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
		}

		GlStateManager._disableDepthTest();
		GlStateManager._depthMask(false);
		GlStateManager._disableBlend();
		GlStateManager._disableCull();
		RenderGlState.colorMask(true, true, true, true);
		GL30.glBindVertexArray(dummyVao);

		copyScene(mainColor, w, h);
		boolean haveCamera = ExperimentalSectionRender.postCameraMatrices(CAMERA_PROJ, CAMERA_VIEW);
		if (haveCamera) {
			CAMERA_INV_PROJ.set(CAMERA_PROJ).invert();
			CAMERA_INV_VIEW.set(CAMERA_VIEW).invert();
		} else {
			CAMERA_INV_PROJ.identity();
			CAMERA_INV_VIEW.identity();
		}

		renderAerial(client, cfg, depth, underwater, post, w, h);
		boolean shafts = renderVolumetrics(client, cfg, depth, underwater, post || debug, haveCamera);
		if (post) renderBloom(cfg, shafts, w, h);
		renderComposite(client, cfg, mainColor, depth, underwater, post, shafts, w, h);

		for (int i = PREV_TEX.length - 1; i >= 0; i--) {
			GlStateManager._activeTexture(GL13.GL_TEXTURE0 + i);
			GlStateManager._bindTexture(PREV_TEX[i]);
			GL33.glBindSampler(i, PREV_SAMPLER[i]);
		}
		GlStateManager._activeTexture(prevActive);

		GlStateManager._glUseProgram(prevProgram);
		GL30.glBindVertexArray(prevVao);
		GlStateManager._viewport(VIEWPORT[0], VIEWPORT[1], VIEWPORT[2], VIEWPORT[3]);

		GlStateManager._blendFuncSeparate(prevBlendSrcRgb, prevBlendDstRgb, prevBlendSrcAlpha, prevBlendDstAlpha);
		GlStateManager._depthFunc(prevDepthFunc);
		GlStateManager._depthMask(prevDepthMask);
		GL11.glCullFace(prevCullFace);
		GL11.glFrontFace(prevFrontFace);
		restoreColorMask(prevColorMask);

		if (prevDepth) GlStateManager._enableDepthTest(); else GlStateManager._disableDepthTest();
		if (prevBlend) GlStateManager._enableBlend(); else GlStateManager._disableBlend();
		if (prevCull) GlStateManager._enableCull(); else GlStateManager._disableCull();

		GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
	}

	private static void copyScene(int mainColor, int w, int h) {
		GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, sceneFboA);
		GlStateManager._viewport(0, 0, w, h);
		GlStateManager._glUseProgram(copyProg);
		bindTex(0, mainColor);
		GL20.glUniform1i(uCopyTex, 0);
		GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
	}

	private static void renderAerial(MinecraftClient client, MoneyakShadersConfig cfg, int depth,
			boolean underwater, boolean post, int w, int h) {
		float fogR, fogG, fogB;
		boolean celestial = ExperimentalSectionRender.postCelestial(CELESTIAL);

		if (celestial && CELESTIAL[4] > 0.5f) {
			fogR = 0.035f;
			fogG = 0.060f;
			fogB = 0.125f;
		} else if (celestial) {
			fogR = 0.50f + CELESTIAL[5] * 0.08f;
			fogG = 0.62f + CELESTIAL[6] * 0.07f;
			fogB = 0.76f + CELESTIAL[7] * 0.05f;
		} else {
			fogR = 0.48f;
			fogG = 0.61f;
			fogB = 0.76f;
		}

		float waterR = UnderwaterFogSync.isActive() ? UnderwaterFogSync.red() : 0.08f;
		float waterG = UnderwaterFogSync.isActive() ? UnderwaterFogSync.green() : 0.32f;
		float waterB = UnderwaterFogSync.isActive() ? UnderwaterFogSync.blue() : 0.50f;

		GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, sceneFboB);
		GlStateManager._viewport(0, 0, w, h);
		GlStateManager._glUseProgram(aerialProg);

		bindTex(0, sceneTexA);
		bindTex(1, depth);
		GL20.glUniform1i(aScene, 0);
		GL20.glUniform1i(aDepth, 1);

		try (MemoryStack stack = MemoryStack.stackPush()) {
			GL20.glUniformMatrix4fv(aInvProj, false, CAMERA_INV_PROJ.get(stack.mallocFloat(16)));
		}

		GL20.glUniform3f(aFogColor, fogR, fogG, fogB);
		GL20.glUniform3f(aUnderwaterColor, waterR, waterG, waterB);
		GL20.glUniform1f(aAtmosphereDensity, Math.max(0f, cfg.atmosphereDensity / 100f));
		GL20.glUniform1f(aAtmosphereHorizon, Math.max(0f, cfg.atmosphereHorizon / 100f));
		GL20.glUniform1f(aUnderwater, underwater ? 1f : 0f);
		GL20.glUniform1f(aPost, post ? 1f : 0f);
		GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
	}

	private static boolean renderVolumetrics(MinecraftClient client, MoneyakShadersConfig cfg, int depth,
			boolean underwater, boolean enabled, boolean haveCamera) {
		GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, shaftsFbo);
		GL30.glClearBufferfv(GL11.GL_COLOR, 0, CLEAR_ZERO);
		if (!enabled || shaftsProg == 0 || cfg.postGodRays <= 0 && cfg.postDebugView == 0 || !haveCamera) return false;

		int shadow0 = ExperimentalSectionRender.postShadowNearTexture();
		int shadow1 = ExperimentalSectionRender.postShadowMidTexture();
		int shadow2 = ExperimentalSectionRender.postShadowFarTexture();
		if (shadow0 <= 0 || shadow1 <= 0 || shadow2 <= 0) return false;

		if (!ExperimentalSectionRender.postShadowMatrices(SHADOW_NEAR, SHADOW_MID, SHADOW_FAR)) return false;
		ExperimentalSectionRender.postShadowOffsets(SHADOW_OFF_NEAR, SHADOW_OFF_MID, SHADOW_OFF_FAR);
		if (!ExperimentalSectionRender.postCelestial(CELESTIAL) || CELESTIAL[3] <= 0.001f) return false;

		int water0 = ExperimentalSectionRender.postShadowWaterNearTexture();
		int water1 = ExperimentalSectionRender.postShadowWaterMidTexture();
		int water2 = ExperimentalSectionRender.postShadowWaterFarTexture();
		if (whiteTex == 0) whiteTex = createWhiteTexture();
		if (water0 <= 0) water0 = whiteTex;
		if (water1 <= 0) water1 = whiteTex;
		if (water2 <= 0) water2 = whiteTex;

		int sw = Math.max(1, tw >> 1), sh = Math.max(1, th >> 1);
		GlStateManager._viewport(0, 0, sw, sh);
		GlStateManager._glUseProgram(shaftsProg);

		bindTex(0, depth);
		bindTex(1, shadow0);
		bindTex(2, shadow1);
		bindTex(3, shadow2);
		bindTex(4, water0);
		bindTex(5, water1);
		bindTex(6, water2);

		GL20.glUniform1i(sDepth, 0);
		for (int i = 0; i < 3; i++) {
			GL20.glUniform1i(sShadow[i], 1 + i);
			GL20.glUniform1i(sWaterShadow[i], 4 + i);
		}

		try (MemoryStack stack = MemoryStack.stackPush()) {
			GL20.glUniformMatrix4fv(sLightMVP[0], false, SHADOW_NEAR.get(stack.mallocFloat(16)));
			GL20.glUniformMatrix4fv(sLightMVP[1], false, SHADOW_MID.get(stack.mallocFloat(16)));
			GL20.glUniformMatrix4fv(sLightMVP[2], false, SHADOW_FAR.get(stack.mallocFloat(16)));
			GL20.glUniformMatrix4fv(sInvProj, false, CAMERA_INV_PROJ.get(stack.mallocFloat(16)));
			GL20.glUniformMatrix4fv(sInvView, false, CAMERA_INV_VIEW.get(stack.mallocFloat(16)));
		}

		GL20.glUniform3f(sShadowOffset[0], SHADOW_OFF_NEAR.x, SHADOW_OFF_NEAR.y, SHADOW_OFF_NEAR.z);
		GL20.glUniform3f(sShadowOffset[1], SHADOW_OFF_MID.x, SHADOW_OFF_MID.y, SHADOW_OFF_MID.z);
		GL20.glUniform3f(sShadowOffset[2], SHADOW_OFF_FAR.x, SHADOW_OFF_FAR.y, SHADOW_OFF_FAR.z);

		var cameraPos = client.gameRenderer.getCamera().getCameraPos();
		GL20.glUniform3f(sCameraWorld, (float)cameraPos.x, (float)cameraPos.y, (float)cameraPos.z);
		GL20.glUniform3f(sLightDir, CELESTIAL[0], CELESTIAL[1], CELESTIAL[2]);
		GL20.glUniform1f(sMoon, CELESTIAL[4] > 0.5f ? 1f : 0f);

		float slider = clamp01(cfg.postGodRays / 100f);
		float strength = (0.10f + (float)Math.sqrt(slider) * 0.62f) * CELESTIAL[3];
		if (CELESTIAL[4] > 0.5f) strength *= 0.68f;
		if (underwater) strength *= 0.42f;
		if (cfg.postDebugView == 1 || cfg.postDebugView >= 3) strength = Math.max(strength, 0.8f);

		GL20.glUniform1f(sStrength, strength);
		GL20.glUniform3f(sColor,
				underwater ? CELESTIAL[5] * 0.48f + 0.10f : CELESTIAL[5],
				underwater ? CELESTIAL[6] * 0.58f + 0.22f : CELESTIAL[6],
				underwater ? CELESTIAL[7] * 0.72f + 0.30f : CELESTIAL[7]);
		GL20.glUniform1f(sWater, underwater ? 1f : 0f);

		float tick = client.getRenderTickCounter().getTickProgress(false);
		float time = client.world != null ? (client.world.getTime() % 1_000_000L + tick) / 20f : 0f;
		float rain = client.world != null ? client.world.getRainGradient(tick) : 0f;

		GL20.glUniform1f(sTime, time);
		GL20.glUniform1f(sNear, 0.05f);
		GL20.glUniform1f(sFar, 1024f);
		GL20.glUniform1f(sDensity, Math.max(0.15f, cfg.atmosphereDensity / 50f));
		GL20.glUniform1f(sRainFactor, rain);
		GL20.glUniform1i(sDebug, cfg.postDebugView);

		cascadeLayout(cfg, CASCADE);
		for (int i = 0; i < 3; i++) GL20.glUniform1f(sCascadeEnd[i], CASCADE[i]);
		GL20.glUniform1f(sCascadeBlend[0], CASCADE[3]);
		GL20.glUniform1f(sCascadeBlend[1], CASCADE[4]);

		GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
		return true;
	}

	private static void renderBloom(MoneyakShadersConfig cfg, boolean shafts, int w, int h) {
		GlStateManager._disableBlend();

		GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, bloomFbo[0]);
		GlStateManager._viewport(0, 0, bloomW[0], bloomH[0]);
		GlStateManager._glUseProgram(bloomPrefilterProg);
		bindTex(0, sceneTexB);
		bindTex(1, shaftsTex);
		GL20.glUniform1i(bpScene, 0);
		GL20.glUniform1i(bpShafts, 1);
		GL20.glUniform1f(bpShaftMix, shafts ? 1f : 0f);
		GL20.glUniform1f(bpThreshold, 0.82f);
		GL20.glUniform1f(bpKnee, 0.42f);
		GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);

		GlStateManager._glUseProgram(bloomDownProg);
		GL20.glUniform1i(bdTex, 0);

		for (int i = 1; i < BLOOM_LEVELS; i++) {
			GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, bloomFbo[i]);
			GlStateManager._viewport(0, 0, bloomW[i], bloomH[i]);
			bindTex(0, bloomTex[i - 1]);
			GL20.glUniform2f(bdTexel, 1f / bloomW[i - 1], 1f / bloomH[i - 1]);
			GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
		}

		GlStateManager._enableBlend();
		GlStateManager._blendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ONE);
		GlStateManager._glUseProgram(bloomUpProg);
		GL20.glUniform1i(buTex, 0);

		for (int i = BLOOM_LEVELS - 1; i > 0; i--) {
			GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, bloomFbo[i - 1]);
			GlStateManager._viewport(0, 0, bloomW[i - 1], bloomH[i - 1]);
			bindTex(0, bloomTex[i]);
			GL20.glUniform2f(buTexel, 1f / bloomW[i], 1f / bloomH[i]);
			GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
		}

		GlStateManager._disableBlend();
	}

	private static void renderComposite(MinecraftClient client, MoneyakShadersConfig cfg, int mainColor, int depth,
			boolean underwater, boolean post, boolean shafts, int w, int h) {
		GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, ioFbo);
		if (ioAttachedColor != mainColor) {
			GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, mainColor, 0);
			GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
			GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
			ioAttachedColor = mainColor;
		}

		GlStateManager._viewport(0, 0, w, h);
		GlStateManager._glUseProgram(compositeProg);

		bindTex(0, sceneTexB);
		bindTex(1, bloomTex[0]);
		bindTex(2, depth);
		bindTex(3, shaftsTex);

		GL20.glUniform1i(cScene, 0);
		GL20.glUniform1i(cBloom, 1);
		GL20.glUniform1i(cDepth, 2);
		GL20.glUniform1i(cShafts, 3);
		GL20.glUniform2f(cTexel, 1f / w, 1f / h);

		GL20.glUniform1f(cBloomStrength, post ? Math.max(0f, cfg.postBloom / 100f) * 0.86f : 0f);
		GL20.glUniform1f(cExposure, Math.max(0.05f, cfg.postExposure / 100f));
		GL20.glUniform1f(cSaturation, Math.max(0f, cfg.postSaturation / 100f));
		GL20.glUniform1f(cVignette, post ? Math.max(0f, cfg.postVignette / 100f) : 0f);
		GL20.glUniform1f(cOutline, post && cfg.celOutlines ? Math.max(0f, cfg.outlineStrength / 100f) : 0f);
		GL20.glUniform1f(cFxaa, post && cfg.fxaa ? 1f : 0f);
		GL20.glUniform1f(cPost, post ? 1f : 0f);
		GL20.glUniform1f(cShaftMix, shafts ? 1f : 0f);
		GL20.glUniform1f(cNear, 0.05f);
		GL20.glUniform1f(cFar, 1024f);
		GL20.glUniform1i(cDebug, cfg.postDebugView);

		GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
	}

	private static void init() {
		try {
			copyProg = GlShader.build(VS, COPY_FS);
			aerialProg = GlShader.build(VS, AERIAL_FS);
			bloomPrefilterProg = GlShader.build(VS, BLOOM_PREFILTER_FS);
			bloomDownProg = GlShader.build(VS, BLOOM_DOWN_FS);
			bloomUpProg = GlShader.build(VS, BLOOM_UP_FS);
			compositeProg = GlShader.build(VS, COMPOSITE_FS);

			try {
				shaftsProg = GlShader.build(VS, SHAFTS_FS);
			} catch (Throwable t) {
				MoneyakShaders.LOGGER.warn("[Plan C/PostFX] volumetric shafts unavailable", t);
				shaftsProg = 0;
			}

			if (copyProg == 0 || aerialProg == 0 || bloomPrefilterProg == 0 || bloomDownProg == 0
					|| bloomUpProg == 0 || compositeProg == 0) {
				compositeProg = 0;
				return;
			}

			uCopyTex = uniform(copyProg, "uTex");

			aScene = uniform(aerialProg, "uScene");
			aDepth = uniform(aerialProg, "uDepth");
			aInvProj = uniform(aerialProg, "uInvProj");
			aFogColor = uniform(aerialProg, "uFogColor");
			aUnderwaterColor = uniform(aerialProg, "uUnderwaterColor");
			aAtmosphereDensity = uniform(aerialProg, "uAtmosphereDensity");
			aAtmosphereHorizon = uniform(aerialProg, "uAtmosphereHorizon");
			aUnderwater = uniform(aerialProg, "uUnderwater");
			aPost = uniform(aerialProg, "uPost");

			bpScene = uniform(bloomPrefilterProg, "uScene");
			bpShafts = uniform(bloomPrefilterProg, "uShafts");
			bpShaftMix = uniform(bloomPrefilterProg, "uShaftMix");
			bpThreshold = uniform(bloomPrefilterProg, "uThreshold");
			bpKnee = uniform(bloomPrefilterProg, "uKnee");

			bdTex = uniform(bloomDownProg, "uTex");
			bdTexel = uniform(bloomDownProg, "uTexel");
			buTex = uniform(bloomUpProg, "uTex");
			buTexel = uniform(bloomUpProg, "uTexel");

			cScene = uniform(compositeProg, "uScene");
			cBloom = uniform(compositeProg, "uBloom");
			cDepth = uniform(compositeProg, "uDepth");
			cShafts = uniform(compositeProg, "uShafts");
			cTexel = uniform(compositeProg, "uTexel");
			cBloomStrength = uniform(compositeProg, "uBloomStrength");
			cExposure = uniform(compositeProg, "uExposure");
			cSaturation = uniform(compositeProg, "uSaturation");
			cVignette = uniform(compositeProg, "uVignette");
			cOutline = uniform(compositeProg, "uOutline");
			cFxaa = uniform(compositeProg, "uFxaa");
			cPost = uniform(compositeProg, "uPost");
			cShaftMix = uniform(compositeProg, "uShaftMix");
			cNear = uniform(compositeProg, "uNear");
			cFar = uniform(compositeProg, "uFar");
			cDebug = uniform(compositeProg, "uDebug");

			if (shaftsProg != 0) {
				sDepth = uniform(shaftsProg, "uDepth");
				sShadow[0] = uniform(shaftsProg, "uShadow0");
				sShadow[1] = uniform(shaftsProg, "uShadow1");
				sShadow[2] = uniform(shaftsProg, "uShadow2");
				sWaterShadow[0] = uniform(shaftsProg, "uWaterShadow0");
				sWaterShadow[1] = uniform(shaftsProg, "uWaterShadow1");
				sWaterShadow[2] = uniform(shaftsProg, "uWaterShadow2");
				sLightMVP[0] = uniform(shaftsProg, "uLightMVP0");
				sLightMVP[1] = uniform(shaftsProg, "uLightMVP1");
				sLightMVP[2] = uniform(shaftsProg, "uLightMVP2");
				sShadowOffset[0] = uniform(shaftsProg, "uShadowOffset0");
				sShadowOffset[1] = uniform(shaftsProg, "uShadowOffset1");
				sShadowOffset[2] = uniform(shaftsProg, "uShadowOffset2");
				sCascadeEnd[0] = uniform(shaftsProg, "uCascadeEnd0");
				sCascadeEnd[1] = uniform(shaftsProg, "uCascadeEnd1");
				sCascadeEnd[2] = uniform(shaftsProg, "uCascadeEnd2");
				sCascadeBlend[0] = uniform(shaftsProg, "uCascadeBlend0");
				sCascadeBlend[1] = uniform(shaftsProg, "uCascadeBlend1");
				sInvProj = uniform(shaftsProg, "uInvProj");
				sInvView = uniform(shaftsProg, "uInvView");
				sCameraWorld = uniform(shaftsProg, "uCameraWorld");
				sLightDir = uniform(shaftsProg, "uLightDir");
				sMoon = uniform(shaftsProg, "uMoon");
				sColor = uniform(shaftsProg, "uColor");
				sStrength = uniform(shaftsProg, "uStrength");
				sWater = uniform(shaftsProg, "uWater");
				sTime = uniform(shaftsProg, "uTime");
				sNear = uniform(shaftsProg, "uNear");
				sFar = uniform(shaftsProg, "uFar");
				sDensity = uniform(shaftsProg, "uDensity");
				sRainFactor = uniform(shaftsProg, "uRainFactor");
				sDebug = uniform(shaftsProg, "uDebug");
			}

			dummyVao = GL30.glGenVertexArrays();
			ioFbo = GL30.glGenFramebuffers();

			MoneyakShaders.LOGGER.info("[Plan C/PostFX] HDR pipeline linked: composite={} volumetric={}",
					compositeProg, shaftsProg);
		} catch (Throwable t) {
			MoneyakShaders.LOGGER.error("[Plan C/PostFX] init failed", t);
			compositeProg = 0;
		}
	}

	private static void ensureTargets(int w, int h) {
		if (w == tw && h == th && sceneTexA != 0 && sceneTexB != 0 && shaftsTex != 0 && bloomTex[0] != 0) return;

		int prevFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
		int prevActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
		GlStateManager._activeTexture(GL13.GL_TEXTURE0);
		int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

		tw = w;
		th = h;
		ioAttachedColor = 0;

		sceneTexA = recreate(sceneTexA, w, h, GL30.GL_RGBA16F);
		sceneTexB = recreate(sceneTexB, w, h, GL30.GL_RGBA16F);
		sceneFboA = attach(sceneFboA, sceneTexA);
		sceneFboB = attach(sceneFboB, sceneTexB);

		int bw = Math.max(1, w >> 1);
		int bh = Math.max(1, h >> 1);
		for (int i = 0; i < BLOOM_LEVELS; i++) {
			bloomW[i] = bw;
			bloomH[i] = bh;
			bloomTex[i] = recreate(bloomTex[i], bw, bh, GL30.GL_RGBA16F);
			bloomFbo[i] = attach(bloomFbo[i], bloomTex[i]);
			bw = Math.max(1, bw >> 1);
			bh = Math.max(1, bh >> 1);
		}

		shaftsTex = recreate(shaftsTex, Math.max(1, w >> 1), Math.max(1, h >> 1), GL30.GL_RGBA16F);
		shaftsFbo = attach(shaftsFbo, shaftsTex);

		GlStateManager._bindTexture(prevTex);
		GlStateManager._activeTexture(prevActive);
		GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
	}

	private static int recreate(int tex, int w, int h, int format) {
		if (tex == 0) tex = GL11.glGenTextures();

		GlStateManager._bindTexture(tex);
		int type = format == GL30.GL_RGBA16F ? GL30.GL_HALF_FLOAT : GL11.GL_UNSIGNED_BYTE;

		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, format, w, h, 0,
				GL11.GL_RGBA, type, (ByteBuffer)null);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
		return tex;
	}

	private static int attach(int fbo, int tex) {
		if (fbo == 0) fbo = GL30.glGenFramebuffers();

		GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
		GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
				GL11.GL_TEXTURE_2D, tex, 0);
		GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
		GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);

		int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
		if (status != GL30.GL_FRAMEBUFFER_COMPLETE)
			MoneyakShaders.LOGGER.warn("[Plan C/PostFX] FBO {} incomplete: 0x{}",
					fbo, Integer.toHexString(status));
		return fbo;
	}

	private static int createWhiteTexture() {
		int prevActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
		GlStateManager._activeTexture(GL13.GL_TEXTURE0);
		int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

		int tex = GL11.glGenTextures();
		GlStateManager._bindTexture(tex);

		try (MemoryStack stack = MemoryStack.stackPush()) {
			var pixel = stack.malloc(1);
			pixel.put(0, (byte)255);
			GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8, 1, 1, 0,
					GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, pixel);
		}

		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

		GlStateManager._bindTexture(prevTex);
		GlStateManager._activeTexture(prevActive);
		return tex;
	}

	private static void cascadeLayout(MoneyakShadersConfig cfg, float[] out) {
		float far = Math.max(64f, cfg.shadowDistanceChunks * 16f);
		float nearMax = Math.max(32f, Math.min(64f, far * 0.42f));
		float near = clamp(cfg.shadowNearChunks * 16f, 32f, nearMax);
		float midMin = near + 32f;
		float midMax = Math.max(midMin, far - 32f);
		float mid = clamp(far * 0.55f, midMin, midMax);
		mid = Math.min(mid, far);

		out[0] = near;
		out[1] = mid;
		out[2] = far;
		out[3] = Math.max(4f, near * 0.12f);
		out[4] = Math.max(6f, mid * 0.10f);
	}

	private static void bindTex(int unit, int tex) {
		GlStateManager._activeTexture(GL13.GL_TEXTURE0 + unit);
		GlStateManager._bindTexture(tex);
		GL33.glBindSampler(unit, 0);
	}

	private static int uniform(int program, String name) {
		return GL20.glGetUniformLocation(program, name);
	}

	private static int colorMaskBits() {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			ByteBuffer mask = stack.malloc(4);
			GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask);
			return (mask.get(0) != 0 ? 1 : 0)
					| (mask.get(1) != 0 ? 2 : 0)
					| (mask.get(2) != 0 ? 4 : 0)
					| (mask.get(3) != 0 ? 8 : 0);
		}
	}

	private static void restoreColorMask(int bits) {
		RenderGlState.colorMask(
				(bits & 1) != 0,
				(bits & 2) != 0,
				(bits & 4) != 0,
				(bits & 8) != 0);
	}

	private static float clamp01(float v) {
		return Math.max(0f, Math.min(1f, v));
	}

	private static float clamp(float v, float min, float max) {
		return v < min ? min : Math.min(v, max);
	}
}
