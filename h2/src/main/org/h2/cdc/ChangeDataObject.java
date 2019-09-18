package org.h2.cdc;

import java.util.HashMap;
import java.util.Map;

public class ChangeDataObject {
    private String type;
    private Map before;
    private Map after;

    public ChangeDataObject(String type) {
        this.type = type;
        before = new HashMap();
        after = new HashMap();
    }

    public String getType() {
        return type;
    }

    public Map getBefore() {
        return before;
    }

    public void setBefore(Map before) {
        this.before = before;
    }

    public Map getAfter() {
        return after;
    }

    public void setAfter(Map after) {
        this.after = after;
    }
}
