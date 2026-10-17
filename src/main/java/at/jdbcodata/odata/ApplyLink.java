package at.jdbcodata.odata;

import org.apache.olingo.commons.api.data.Entity;
import org.apache.olingo.commons.api.data.EntityCollection;
import org.apache.olingo.commons.api.data.Link;

/**
 * A navigation link created by a $apply transformation (join/outerjoin alias, nest alias, expand): expressions
 * that follow it use the rows attached here instead of executing the relation again.
 */
final class ApplyLink extends Link {

    static ApplyLink single(String name, Entity target) {
        ApplyLink l = new ApplyLink();
        l.setTitle(name);
        l.setInlineEntity(target);
        return l;
    }

    static ApplyLink collection(String name, java.util.List<Entity> targets) {
        ApplyLink l = new ApplyLink();
        l.setTitle(name);
        EntityCollection c = new EntityCollection();
        c.getEntities().addAll(targets);
        c.setCount(targets.size());
        l.setInlineEntitySet(c);
        return l;
    }
}
