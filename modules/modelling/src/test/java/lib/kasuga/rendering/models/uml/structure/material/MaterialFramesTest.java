package lib.kasuga.rendering.models.uml.structure.material;
import org.joml.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class MaterialFramesTest {
    @Test void indexesEverySpriteSetAndClampsToLastValidFrame() {
        Texture texture=new Texture("fixture",1,1,null);
        Sprite a=sprite(texture), b=sprite(texture);
        Material first=new Material(new Texture[]{texture},null), second=new Material(new Texture[]{texture},null);
        SpriteSet a0=new SpriteSet(null,a), a1=new SpriteSet(null,a,b), b0=new SpriteSet(null,b);
        first.addSprite(a0); first.addSprite(a1); second.addSprite(b0);
        MaterialSet set=new MaterialSet(List.of(texture),List.of(first,second));
        assertEquals(3,set.getSpriteSets().length);
        assertSame(first,set.getMaterial(a1)); assertSame(second,set.getMaterial(b0));
        MaterialSetInstance instance=new MaterialSetInstance(set);
        instance.setCurrentMatFrame(first,99); instance.setCurrentSpriteFrame(first,99);
        assertEquals(1,instance.getCurrentMatFrame(first)); assertEquals(1,instance.getCurrentSpriteFrame(first));
        assertSame(b,instance.getSprite(first));
        BitSet changes=new BitSet();long version=instance.getChanges().collectSince(0,changes);
        instance.clearDirty();assertFalse(changes.isEmpty());
        instance.setCurrentSpriteFrame(first,99); assertEquals(version,instance.getChanges().version());
        instance.setCurrentMatFrame(first,-99); assertSame(a,instance.getSprite(first));
        MaterialSet single=new MaterialSet(texture,first);
        assertEquals(0,single.getIndexByMaterial().get(first));assertSame(first,single.getMaterial(a1));
    }
    private static Sprite sprite(Texture texture) {
        return new Sprite(texture,new Vector2f(),new Vector2f(1,0),new Vector2f(1,1),new Vector2f(0,1),null,null,null,null);
    }
}
