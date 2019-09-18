package org.h2.cdc;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

class CDOStringConverter {

    static String ConvertCDOToString(ChangeDataObject changeDataObject) {

        String type = changeDataObject.getType();
        Map before = changeDataObject.getBefore();
        Map after = changeDataObject.getAfter();

        String strBefore = before.toString();
        String strAfter = after.toString();

        return "[" + (" type: " + type + ", before: " + strBefore + ", after: " + strAfter + " ]");
    }


    static ChangeDataObject ConvertStringToCDO(String appended) {

        String[] namepass = appended.split(":");
        String type = namepass[1].split(",")[0];
        String strBefore = namepass[2].split(",")[0];
        String strAfter = namepass[3].substring(0, namepass[3].length() - 1);

        Map before = convertToStringToHashMap(strBefore);
        Map after = convertToStringToHashMap(strAfter);

        ChangeDataObject changeDataObject = new ChangeDataObject(type);
        changeDataObject.setBefore(before);
        changeDataObject.setAfter(after);
        return changeDataObject;
    }

    private static HashMap<String, String> convertToStringToHashMap(String text) {
        HashMap<String, String> data = new HashMap<>();
        Pattern p = Pattern.compile("[\\{\\}\\=\\, ]++");
        String[] split = p.split(text);
        for (int i = 1; i + 2 <= split.length; i += 2) {
            data.put(split[i], split[i + 1]);
        }
        return data;
    }
}
