// 脚本只构建 IR；像素计算由生成的 GLSL 执行。
const tintParameter = ShaderParameter.builder(
    "Tint", "画面色调", ShaderParameterType.COLOR_RGB
).range(0, 1).defaultValues(1, 0.5, 0.25).build();

Shader.fullscreen("example:script_tint", shader => {
    const tint = shader.exposeVec3(tintParameter);
    const source = shader.sampler2D("SceneSampler").sample(shader.texCoord());
    shader.fragmentColor(shader.vec4(source.rgb().mul(tint), source.a()));
});
