#define GL_SILENCE_DEPRECATION
#include <OpenGL/OpenGL.h>
#include <OpenGL/gl3.h>
#include <stdio.h>
#include <stdlib.h>
#include <math.h>

/* macOS GPU regression (not part of the pure-Java test task).
 * clang peel_depth_precision.c -framework OpenGL -o /tmp/peel-depth-test
 * /tmp/peel-depth-test
 * Add -DKSG_REPRO_IMPLICIT_DEPTH to reproduce the old failure on affected GPUs.
 * A single triangle must produce fragments only in its first peel.
 */
#ifdef KSG_REPRO_IMPLICIT_DEPTH
#define DEPTH_WRITE ""
#else
#define DEPTH_WRITE "gl_FragDepth=gl_FragCoord.z;"
#endif
#define W 128
static GLuint shader(GLenum type,const char*src){GLuint id=glCreateShader(type);glShaderSource(id,1,&src,NULL);glCompileShader(id);GLint ok;glGetShaderiv(id,GL_COMPILE_STATUS,&ok);if(!ok){char log[4096];glGetShaderInfoLog(id,4096,NULL,log);puts(log);exit(2);}return id;}
static GLuint texture(GLint format, GLenum pixels){GLuint id;glGenTextures(1,&id);glBindTexture(GL_TEXTURE_2D,id);glTexImage2D(GL_TEXTURE_2D,0,format,W,W,0,pixels,GL_FLOAT,NULL);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);return id;}
int main(void){
 CGLPixelFormatAttribute attrs[]={kCGLPFAOpenGLProfile,(CGLPixelFormatAttribute)kCGLOGLPVersion_3_2_Core,0};CGLPixelFormatObj pf;CGLContextObj ctx;GLint n;
 if(CGLChoosePixelFormat(attrs,&pf,&n)||CGLCreateContext(pf,NULL,&ctx))return 1;CGLSetCurrentContext(ctx);
 GLuint fb[2],depth[2],color[2],vao;glGenFramebuffers(2,fb);glGenVertexArrays(1,&vao);glBindVertexArray(vao);
 for(int i=0;i<2;i++){glBindFramebuffer(GL_FRAMEBUFFER,fb[i]);depth[i]=texture(GL_DEPTH_COMPONENT32F,GL_DEPTH_COMPONENT);color[i]=texture(GL_R32F,GL_RED);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_TEXTURE_2D,depth[i],0);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,color[i],0);if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)return 3;}
 GLuint vs=shader(GL_VERTEX_SHADER,"#version 150\nuniform float base;void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2-1,base+p.x*.00003+p.y*.00007,1);}");
 GLuint fs=shader(GL_FRAGMENT_SHADER,"#version 150\nuniform sampler2D previous;out float color;void main(){if(gl_FragCoord.z<=texelFetch(previous,ivec2(gl_FragCoord.xy),0).r)discard;" DEPTH_WRITE "color=gl_FragCoord.z;}");
 GLuint program=glCreateProgram();glAttachShader(program,vs);glAttachShader(program,fs);glLinkProgram(program);glUseProgram(program);glUniform1i(glGetUniformLocation(program,"previous"),0);
 glViewport(0,0,W,W);glEnable(GL_DEPTH_TEST);glDepthFunc(GL_LESS);glDepthMask(GL_TRUE);
 GLuint queries[4];glGenQueries(4,queries);float depths[W*W],colors[W*W];int duplicates=0;
 for(int useColor=0;useColor<2;useColor++) for(int test=0;test<8;test++){
  int prev=0,cur=1;glBindFramebuffer(GL_FRAMEBUFFER,fb[prev]);glClearDepth(0);glClearColor(0,0,0,0);glClear(GL_DEPTH_BUFFER_BIT|GL_COLOR_BUFFER_BIT);
  glUniform1f(glGetUniformLocation(program,"base"),.95f+test*.006f);
  for(int layer=0;layer<4;layer++){
   glBindFramebuffer(GL_FRAMEBUFFER,fb[cur]);glClearDepth(1);glClearColor(1,0,0,0);glClear(GL_DEPTH_BUFFER_BIT|GL_COLOR_BUFFER_BIT);glBindTexture(GL_TEXTURE_2D,useColor?color[prev]:depth[prev]);
   glBeginQuery(GL_SAMPLES_PASSED,queries[layer]);glDrawArrays(GL_TRIANGLES,0,3);glEndQuery(GL_SAMPLES_PASSED);
   if(layer==0){glReadPixels(0,0,W,W,GL_DEPTH_COMPONENT,GL_FLOAT,depths);glReadPixels(0,0,W,W,GL_RED,GL_FLOAT,colors);int lower=0,higher=0;float max=0;for(int p=0;p<W*W;p++){lower+=depths[p]<colors[p];higher+=depths[p]>colors[p];max=fmaxf(max,fabsf(depths[p]-colors[p]));}printf("%s test=%d depth<frag=%d depth>frag=%d maxDiff=%g ",useColor?"color":"depth",test,lower,higher,max);}
   int swap=prev;prev=cur;cur=swap;
  }
  for(int layer=0;layer<4;layer++){GLuint samples;glGetQueryObjectuiv(queries[layer],GL_QUERY_RESULT,&samples);printf("layer%d=%u ",layer,samples);if(layer>0&&samples)duplicates++;}puts("");
 }
 GLenum error=glGetError(); printf("GL error=0x%x duplicate passes=%d\n",error,duplicates);
 CGLSetCurrentContext(NULL);CGLDestroyContext(ctx);CGLDestroyPixelFormat(pf);return error!=GL_NO_ERROR || duplicates!=0;
}
