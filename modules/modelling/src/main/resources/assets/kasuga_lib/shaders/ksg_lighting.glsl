// Match Direction.getNearest for the world-space face normal, including its Y/Z/X tie order.
float ksg_block_shade(vec3 normal, vec4 shades) {
    if (!(length(normal) > 0.000001)) return shades.y;
    vec3 axis = abs(normal);
    if (axis.y >= axis.z && axis.y >= axis.x) return normal.y < 0.0 ? shades.x : shades.y;
    return axis.z >= axis.x ? shades.z : shades.w;
}
