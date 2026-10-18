package lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.basic;

import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.basic.numbers.*;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.basic.vectors.Vec2fLoader;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.basic.vectors.Vec3fLoader;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.basic.vectors.Vec4fLoader;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public class BasicLoaders {

    public static final ByteLoader BYTE = new ByteLoader();
    public static final UByteLoader UBYTE = new UByteLoader();
    public static final ShortLoader SHORT = new ShortLoader();
    public static final UShortLoader USHORT = new UShortLoader();
    public static final IntLoader INT = new IntLoader();
    public static final UIntLoader UINT = new UIntLoader();
    public static final FloatLoader FLOAT = new FloatLoader();

    public static final Vec2fLoader VEC2F = new Vec2fLoader();
    public static final Vec3fLoader VEC3F = new Vec3fLoader();
    public static final Vec4fLoader VEC4F = new Vec4fLoader();

    public static final FlagsLoader FLAGS = new FlagsLoader();

    public static final TextLoader UTF8_TEXT = new TextLoader(StandardCharsets.UTF_8);
    public static final TextLoader UTF16_TEXT = new TextLoader(StandardCharsets.UTF_16);
    public static final TextLoader UTF16LE_TEXT = new TextLoader(StandardCharsets.UTF_16LE);
    public static final TextLoader UTF16BE_TEXT = new TextLoader(StandardCharsets.UTF_16BE);
    public static final TextLoader ASCII_TEXT = new TextLoader(StandardCharsets.US_ASCII);

    public static Padding padding(int length) {
        return new Padding(length);
    }

    public static TextLoader text(Charset charset) {
        if (charset.equals(StandardCharsets.UTF_8)) {
            return UTF8_TEXT;
        } else if (charset.equals(StandardCharsets.UTF_16)) {
            return UTF16_TEXT;
        } else if (charset.equals(StandardCharsets.UTF_16LE)) {
            return UTF16LE_TEXT;
        } else if (charset.equals(StandardCharsets.UTF_16BE)) {
            return UTF16BE_TEXT;
        } else if (charset.equals(StandardCharsets.US_ASCII)) {
            return ASCII_TEXT;
        }
        return new TextLoader(charset);
    }
}
