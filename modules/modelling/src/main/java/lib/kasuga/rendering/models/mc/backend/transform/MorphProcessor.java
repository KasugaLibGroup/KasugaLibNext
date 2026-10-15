package lib.kasuga.rendering.models.mc.backend.transform;

import com.mojang.blaze3d.vertex.VertexFormatElement;
import lib.kasuga.rendering.models.mc.backend.RenderState;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;

/**
 * 管理模型的顶点属性展开列，支持 Morphing 系统每帧修改特定顶点属性。
 * 对于 CPU 蒙皮，基础数据从此类读取；对于 GPU 蒙皮，修改直接应用到最终缓冲区。
 */
@Deprecated
public class MorphProcessor {

//    private final ModelInstance model;
//    // 顶点总数
//    private final int vertexCount;
//    // 原始顶点缓冲区(用于 GPU 蒙皮时直接写入)
//    private final ByteBuffer buffer;
//    private final int vertexSize;
//    private final Map<VertexFormatElement, Integer> bufOffsets; // 元素在缓冲区中的偏移
//
//    // 展开列：位置(x3), 法线(x3), 切线(x4), UV0(x2), UV1(x2), UV2(x2) 等
//    private float[] positions;    // length = vertexCount * 3
//    private float[] normals;      // length = vertexCount * 3
//    private float[] tangents;     // length = vertexCount * 4
//    private float[] uv0;          // length = vertexCount * 2
//    private float[] uv1;          // length = vertexCount * 2
//    private float[] uv2;          // length = vertexCount * 2
//
//    // 脏标记：哪些顶点的基础数据被修改了（位置/法线/切线）
//    private final BitSet dirtyVertices = new BitSet();
//
//    // 偏移量（方便写入buffer）
//    private final int posOffset, normOffset, tanOffset, uv0Offset, uv1Offset, uv2Offset;
//
//    public MorphProcessor(ByteBuffer buffer, int vertexSize, Map<VertexFormatElement, Integer> bufOffsets, ModelInstance model) {
//        this.buffer = buffer;
//        this.model = model;
//        this.vertexSize = vertexSize;
//        this.bufOffsets = bufOffsets;
//
//        this.posOffset = bufOffsets.get(VertexFormatElement.POSITION);
//        this.normOffset = bufOffsets.get(VertexFormatElement.NORMAL);
//        this.tanOffset = bufOffsets.get(RenderState.TANGENT);
//        this.uv0Offset = bufOffsets.get(VertexFormatElement.UV0);
//        this.uv1Offset = bufOffsets.get(VertexFormatElement.UV1);
//        this.uv2Offset = bufOffsets.get(VertexFormatElement.UV2);
//
//        // 初始化展开列：从缓冲区读取当前值
//        positions = new float[vertexCount * 3];
//        normals = new float[vertexCount * 3];
//        tangents = new float[vertexCount * 4];
//        uv0 = new float[vertexCount * 2];
//        uv1 = new float[vertexCount * 2];
//        uv2 = new float[vertexCount * 2];
//
//        for (int i = 0; i < vertexCount; i++) {
//            int base = i * vertexSize;
//            // 位置
//            int pOff = base + posOffset;
//            positions[i*3] = buffer.getFloat(pOff);
//            positions[i*3+1] = buffer.getFloat(pOff+4);
//            positions[i*3+2] = buffer.getFloat(pOff+8);
//            // 法线
//            int nOff = base + normOffset;
//            normals[i*3] = ((float) buffer.get(nOff)) / 127f;
//            normals[i*3+1] = ((float) buffer.get(nOff+1)) / 127f;
//            normals[i*3+2] = ((float) buffer.get(nOff+2)) / 127f;
//            // 切线
//            int tOff = base + tanOffset;
//            tangents[i*4] = buffer.getFloat(tOff);
//            tangents[i*4+1] = buffer.getFloat(tOff+4);
//            tangents[i*4+2] = buffer.getFloat(tOff+8);
//            tangents[i*4+3] = buffer.getFloat(tOff+12);
//            // UV
//            int uv0Off = base + uv0Offset;
//            uv0[i*2] = buffer.getFloat(uv0Off);
//            uv0[i*2+1] = buffer.getFloat(uv0Off+4);
//            if (uv1Offset >= 0) {
//                int uv1Off = base + uv1Offset;
//                uv1[i*2] = buffer.getFloat(uv1Off);
//                uv1[i*2+1] = buffer.getFloat(uv1Off+4);
//            }
//            if (uv2Offset >= 0) {
//                int uv2Off = base + uv2Offset;
//                uv2[i*2] = buffer.getFloat(uv2Off);
//                uv2[i*2+1] = buffer.getFloat(uv2Off+4);
//            }
//        }
//    }
//
//    // ---------- 修改接口 ----------
//    public void setPosition(int vertexIndex, float x, float y, float z) {
//        positions[vertexIndex*3] = x;
//        positions[vertexIndex*3+1] = y;
//        positions[vertexIndex*3+2] = z;
//        dirtyVertices.set(vertexIndex);
//    }
//
//    public void setNormal(int vertexIndex, float x, float y, float z) {
//        normals[vertexIndex*3] = x;
//        normals[vertexIndex*3+1] = y;
//        normals[vertexIndex*3+2] = z;
//        dirtyVertices.set(vertexIndex);
//    }
//
//    public void setTangent(int vertexIndex, float x, float y, float z, float w) {
//        tangents[vertexIndex*4] = x;
//        tangents[vertexIndex*4+1] = y;
//        tangents[vertexIndex*4+2] = z;
//        tangents[vertexIndex*4+3] = w;
//        dirtyVertices.set(vertexIndex);
//    }
//
//    public void setUV0(int vertexIndex, float u, float v) {
//        uv0[vertexIndex*2] = u;
//        uv0[vertexIndex*2+1] = v;
//        // UV 不需要触发蒙皮脏标记，但需要更新 buffer
//        applyUV0ToBuffer(vertexIndex);
//    }
//
//    public void setUV1(int vertexIndex, float u, float v) {
//        if (uv1Offset >= 0) {
//            uv1[vertexIndex*2] = u;
//            uv1[vertexIndex*2+1] = v;
//            applyUV1ToBuffer(vertexIndex);
//        }
//    }
//
//    public void setUV2(int vertexIndex, float u, float v) {
//        if (uv2Offset >= 0) {
//            uv2[vertexIndex*2] = u;
//            uv2[vertexIndex*2+1] = v;
//            applyUV2ToBuffer(vertexIndex);
//        }
//    }
//
//    // 批量设置 UV（用于整个面片）
//    public void setUV0ForMesh(Mesh mesh, float u, float v) {
//        for (Vertex vertex : mesh.getVertices()) {
//            int idx = getVertexIndex(vertex, mesh); // 需要映射，这里简化
//            if (idx >= 0) setUV0(idx, u, v);
//        }
//    }
//
//    // 获取当前值（供 CPU 蒙皮读取）
//    public void getPosition(int vertexIndex, Vector3f out) {
//        int off = vertexIndex * 3;
//        out.set(positions[off], positions[off+1], positions[off+2]);
//    }
//    public void getNormal(int vertexIndex, Vector3f out) {
//        int off = vertexIndex * 3;
//        out.set(normals[off], normals[off+1], normals[off+2]);
//    }
//    public void getTangent(int vertexIndex, Vector4f out) {
//        int off = vertexIndex * 4;
//        out.set(tangents[off], tangents[off+1], tangents[off+2], tangents[off+3]);
//    }
//
//    // 直接应用修改到缓冲区（用于 GPU 蒙皮或 UV 等立即生效的属性）
//    private void applyPositionToBuffer(int vertexIndex) {
//        int base = vertexIndex * vertexSize + posOffset;
//        buffer.putFloat(base, positions[vertexIndex*3]);
//        buffer.putFloat(base+4, positions[vertexIndex*3+1]);
//        buffer.putFloat(base+8, positions[vertexIndex*3+2]);
//    }
//    private void applyNormalToBuffer(int vertexIndex) {
//        int base = vertexIndex * vertexSize + normOffset;
//        buffer.put(base, (byte)(normals[vertexIndex*3] * 127));
//        buffer.put(base+1, (byte)(normals[vertexIndex*3+1] * 127));
//        buffer.put(base+2, (byte)(normals[vertexIndex*3+2] * 127));
//    }
//    private void applyTangentToBuffer(int vertexIndex) {
//        int base = vertexIndex * vertexSize + tanOffset;
//        buffer.putFloat(base, tangents[vertexIndex*4]);
//        buffer.putFloat(base+4, tangents[vertexIndex*4+1]);
//        buffer.putFloat(base+8, tangents[vertexIndex*4+2]);
//        buffer.putFloat(base+12, tangents[vertexIndex*4+3]);
//    }
//    private void applyUV0ToBuffer(int vertexIndex) {
//        int base = vertexIndex * vertexSize + uv0Offset;
//        buffer.putFloat(base, uv0[vertexIndex*2]);
//        buffer.putFloat(base+4, uv0[vertexIndex*2+1]);
//    }
//    private void applyUV1ToBuffer(int vertexIndex) {
//        if (uv1Offset >= 0) {
//            int base = vertexIndex * vertexSize + uv1Offset;
//            buffer.putFloat(base, uv1[vertexIndex*2]);
//            buffer.putFloat(base+4, uv1[vertexIndex*2+1]);
//        }
//    }
//    private void applyUV2ToBuffer(int vertexIndex) {
//        if (uv2Offset >= 0) {
//            int base = vertexIndex * vertexSize + uv2Offset;
//            buffer.putFloat(base, uv2[vertexIndex*2]);
//            buffer.putFloat(base+4, uv2[vertexIndex*2+1]);
//        }
//    }
//
//    // 批量将脏顶点的位置/法线/切线应用到缓冲区（用于 GPU 蒙皮模式）
//    public void flushDirtyVerticesToBuffer() {
//        for (int idx = dirtyVertices.nextSetBit(0); idx >= 0; idx = dirtyVertices.nextSetBit(idx+1)) {
//            applyPositionToBuffer(idx);
//            applyNormalToBuffer(idx);
//            applyTangentToBuffer(idx);
//        }
//        dirtyVertices.clear();
//    }
//
//    // 获取脏顶点集合并清除（用于 CPU 蒙皮读取变化）
//    public BitSet consumeDirtyVertices() {
//        BitSet copy = (BitSet) dirtyVertices.clone();
//        dirtyVertices.clear();
//        return copy;
//    }
//
//    // 辅助：获取顶点在缓冲区中的索引（需要 vertexMap）
//    private int getVertexIndex(Vertex vertex, Mesh mesh) {
//        // 实际应通过 vertexMap 查找，这里简化
//        return -1;
//    }
}
