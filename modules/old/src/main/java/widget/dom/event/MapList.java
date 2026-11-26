package lib.kasuga.widget.dom.event;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class MapList<K, V> extends HashMap<K, List<V>> {
    public void addEntry(K key, V value) {
        List<V> values = this.get(key);
        if(values != null) {
            values.add(value);
        } else {
            values = new ArrayList<>();
            values.add(value);
            this.put(key, values);
        }
    }

    public void removeEntry(K key, V value) {
        List<V> values = this.get(key);
        if(values != null) {
            values.remove(value);
            if(values.isEmpty()) {
                this.remove(key);
            }
        }
    }
}
