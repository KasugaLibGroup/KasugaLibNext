package lib.kasuga.widget.renderer.model;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;

import javax.annotation.Nullable;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

public enum RotationMode {

    XYZ((q,v)->q.rotateXYZ(v.x, v.y, v.z), RotationAxis.X, RotationAxis.Y, RotationAxis.Z),
    XZY(RotationAxis.X, RotationAxis.Z, RotationAxis.Y),
    ZXY(RotationAxis.Z, RotationAxis.X, RotationAxis.Y),
    ZYX((q,v)->q.rotateXYZ(v.z, v.y, v.x), RotationAxis.Z, RotationAxis.Y, RotationAxis.X),
    YXZ((q,v)->q.rotateYXZ(v.y, v.x, v.z), RotationAxis.Y, RotationAxis.X, RotationAxis.Z),
    YZX(RotationAxis.Y, RotationAxis.Z, RotationAxis.X);


    private final @Nullable QuickRotationFunction fastRotationFunction;

    protected enum RotationAxis {
        X((q,r)->q.rotationX(r.x())),
        Y((q,r)->q.rotationY(r.y())),
        Z((q,r)->q.rotationZ(r.z()));
        private final BiConsumer<Quaterniond, Vec3> rotateFunction;

        RotationAxis(BiConsumer<Quaterniond, Vec3> rotateFunction) {
            this.rotateFunction = rotateFunction;
        }

        public void rotate(Quaterniond quaterniond, Vec3 v) {
            this.rotateFunction.accept(quaterniond, v);
        }
    }

    protected interface QuickRotationFunction {
        public void rotate(Quaterniond quaterniond, Vec3 vector);
    }

    private RotationAxis[] rotationAxis;


    RotationMode(RotationAxis ...rotationAxis) {
        this.rotationAxis = rotationAxis;
        this.fastRotationFunction = null;
    }

    RotationMode(QuickRotationFunction fastFunction, RotationAxis ...rotationAxis) {
        this.rotationAxis = rotationAxis;
        this.fastRotationFunction = fastFunction;
    }

    public void rotate(Quaterniond quaternion, Vec3 rotationVector) {
        if(this.fastRotationFunction != null) {
            this.fastRotationFunction.rotate(quaternion, rotationVector);
            return;
        }
        this.rotationAxis[0].rotate(quaternion, rotationVector);
        this.rotationAxis[1].rotate(quaternion, rotationVector);
        this.rotationAxis[2].rotate(quaternion, rotationVector);
    }
}
