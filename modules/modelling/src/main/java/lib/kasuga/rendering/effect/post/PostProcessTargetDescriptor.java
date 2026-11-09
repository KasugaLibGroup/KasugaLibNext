package lib.kasuga.rendering.effect.post;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.List;

/** Immutable allocation policy for a reusable post-processing texture target. */
public record PostProcessTargetDescriptor(
        ResourceLocation id,
        SizeMode sizeMode,
        float width,
        float height,
        boolean useDepth,
        TextureFilter filter,
        float clearRed,
        float clearGreen,
        float clearBlue,
        float clearAlpha,
        List<ColorFormat> colorAttachments
) {
    public PostProcessTargetDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sizeMode, "sizeMode");
        Objects.requireNonNull(filter, "filter");
        colorAttachments = List.copyOf(colorAttachments);
        if (colorAttachments.isEmpty() || colorAttachments.size() > 8) {
            throw new IllegalArgumentException("A target requires between one and eight color attachments");
        }
        if (!Float.isFinite(width) || !Float.isFinite(height) || !(width > 0) || !(height > 0)) {
            throw new IllegalArgumentException("Post-process target dimensions must be positive");
        }
    }

    /** Retains the single RGBA8 attachment allocation policy of the original API. */
    public PostProcessTargetDescriptor(ResourceLocation id, SizeMode sizeMode, float width, float height,
                                       boolean useDepth, TextureFilter filter,
                                       float clearRed, float clearGreen, float clearBlue, float clearAlpha) {
        this(id, sizeMode, width, height, useDepth, filter, clearRed, clearGreen, clearBlue, clearAlpha,
                List.of(ColorFormat.RGBA8));
    }

    public static Builder builder(ResourceLocation id) {
        return new Builder(id);
    }

    public PostProcessTargetDescriptor withId(ResourceLocation newId) {
        return new PostProcessTargetDescriptor(
                Objects.requireNonNull(newId, "newId"), sizeMode, width, height, useDepth, filter,
                clearRed, clearGreen, clearBlue, clearAlpha, colorAttachments
        );
    }

    public int resolveWidth(int referenceWidth) {
        return sizeMode == SizeMode.SCREEN_RELATIVE
                ? Math.max(1, Math.round(referenceWidth * width))
                : Math.max(1, Math.round(width));
    }

    public int resolveHeight(int referenceHeight) {
        return sizeMode == SizeMode.SCREEN_RELATIVE
                ? Math.max(1, Math.round(referenceHeight * height))
                : Math.max(1, Math.round(height));
    }

    public enum SizeMode {
        SCREEN_RELATIVE,
        FIXED
    }

    /** OpenGL core formats, without requiring an active context to describe a graph. */
    public enum ColorFormat {
        RGBA8(0x8058, 0x1401),
        RGBA16F(0x881A, 0x1406),
        RGBA32F(0x8814, 0x1406);

        private final int internalFormat, pixelType;
        ColorFormat(int internalFormat, int pixelType) {
            this.internalFormat = internalFormat;
            this.pixelType = pixelType;
        }
        public int internalFormat() { return internalFormat; }
        public int pixelType() { return pixelType; }
    }

    public enum TextureFilter {
        NEAREST(0x2600),
        LINEAR(0x2601);

        private final int glConstant;

        TextureFilter(int glConstant) {
            this.glConstant = glConstant;
        }

        public int glConstant() {
            return glConstant;
        }
    }

    public static final class Builder {
        private final ResourceLocation id;
        private SizeMode sizeMode = SizeMode.SCREEN_RELATIVE;
        private float width = 1.0f;
        private float height = 1.0f;
        private boolean useDepth;
        private TextureFilter filter = TextureFilter.NEAREST;
        private float clearRed;
        private float clearGreen;
        private float clearBlue;
        private float clearAlpha;
        private List<ColorFormat> colorAttachments = List.of(ColorFormat.RGBA8);

        private Builder(ResourceLocation id) {
            this.id = Objects.requireNonNull(id, "id");
        }

        public Builder screenScale(float scale) {
            return screenScale(scale, scale);
        }

        public Builder screenScale(float widthScale, float heightScale) {
            sizeMode = SizeMode.SCREEN_RELATIVE;
            width = widthScale;
            height = heightScale;
            return this;
        }

        public Builder fixedSize(int width, int height) {
            sizeMode = SizeMode.FIXED;
            this.width = width;
            this.height = height;
            return this;
        }

        public Builder useDepth(boolean value) {
            useDepth = value;
            return this;
        }

        public Builder filter(TextureFilter value) {
            filter = Objects.requireNonNull(value, "filter");
            return this;
        }

        public Builder colorAttachments(ColorFormat... formats) {
            colorAttachments = List.of(formats);
            return this;
        }

        public Builder clearColor(float red, float green, float blue, float alpha) {
            clearRed = red;
            clearGreen = green;
            clearBlue = blue;
            clearAlpha = alpha;
            return this;
        }

        public PostProcessTargetDescriptor build() {
            return new PostProcessTargetDescriptor(
                    id, sizeMode, width, height, useDepth, filter,
                    clearRed, clearGreen, clearBlue, clearAlpha, colorAttachments
            );
        }
    }
}
