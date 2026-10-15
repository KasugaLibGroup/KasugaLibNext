package lib.kasuga.rendering.models.mc.typo.mtb;

import lib.kasuga.rendering.models.uml.loaders.serial.ContextData;
import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MtbContext implements ContextData<MtbContext> {

    public final List<MtbPolygon> polygons;
    public final Map<String, List<MtbPolygon>> groups;
    public int textureWidth, textureHeight;
    public String modelAuthor, modelName;

    public MtbContext() {
        this.groups = new HashMap<>();
        this.polygons = new ArrayList<>();
    }

    public boolean containsGroup(String groupName) {
        return groups.containsKey("group" + groupName);
    }

    public void addPolygon(MtbPolygon polygon) {
        polygons.add(polygon);
        if (polygon.group != null) {
            groups.computeIfAbsent("group" + polygon.group, k -> new ArrayList<>()).add(polygon);
        }
    }

    @Override
    public void build(SerialContext<MtbContext> context) {

    }
}
