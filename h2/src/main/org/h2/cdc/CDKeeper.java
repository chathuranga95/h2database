package org.h2.cdc;

import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

public class CDKeeper {
    private static List<ChangeDataObject> changeDataObjects = new ArrayList<>();
    private static String lastchange;

    private static Preferences prefs = Preferences.userNodeForPackage(CDKeeper.class);
    private static String KEY = "CDCOBJKEY";
    private static String DEF = "CDCOBJDEFAULT";
    private static String EMPTYCDSTRING = "emptyCDString";
    private static String STRINGSPLITTER = "_";

    public static List<ChangeDataObject> getLatestEvents() {
        String rowString = prefs.get(KEY, DEF);
        List<ChangeDataObject> cdcObjects = new ArrayList<>();
        if (rowString.equals(EMPTYCDSTRING) || rowString.equals(DEF)) {
            return cdcObjects;
        } else {
            String[] cdcStrings = rowString.split(STRINGSPLITTER);
            prefs.put(KEY, EMPTYCDSTRING);
            for (String cdcString : cdcStrings) {
                cdcObjects.add(CDOStringConverter.ConvertStringToCDO(cdcString));
            }
            return cdcObjects;
        }
    }

    public static void appendChangeData(ChangeDataObject changeDataObject) {
        String cdString = prefs.get(KEY, DEF);
        if (cdString.equals(DEF) || cdString.equals(EMPTYCDSTRING)) {
            prefs.put(KEY, CDOStringConverter.ConvertCDOToString(changeDataObject));
        } else {
            prefs.put(KEY, cdString.concat(STRINGSPLITTER).concat(
                    CDOStringConverter.ConvertCDOToString(changeDataObject)));
        }
    }

    public static String getLastchange() {
        Preferences prefs = Preferences.userNodeForPackage(CDKeeper.class);
        return prefs.get("MYCDCStr", "default");
    }

    public static void setLastchange(String lastchange) {
        CDKeeper.lastchange = lastchange;
        Preferences prefs = Preferences.userNodeForPackage(CDKeeper.class);
        prefs.put("MYCDCStr", lastchange);
    }
}
