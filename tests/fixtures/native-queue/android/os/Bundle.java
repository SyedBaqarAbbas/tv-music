package android.os;
import java.util.ArrayList;
import java.util.HashMap;
/** Value container fixture, not an Android lifecycle or Parcelable emulator. */
public final class Bundle {
    private final HashMap<String,Object> values = new HashMap<>();
    public void putString(String k,String v){values.put(k,v);}
    public String getString(String k){return getString(k,null);}
    public String getString(String k,String d){Object v=values.get(k);return v instanceof String?(String)v:d;}
    public void putBoolean(String k,boolean v){values.put(k,v);}
    public boolean getBoolean(String k){Object v=values.get(k);return v instanceof Boolean&&(Boolean)v;}
    public boolean getBoolean(String k,boolean d){return values.containsKey(k)?getBoolean(k):d;}
    public void putInt(String k,int v){values.put(k,v);}
    public int getInt(String k){return getInt(k,0);}
    public int getInt(String k,int d){Object v=values.get(k);return v instanceof Number?((Number)v).intValue():d;}
    public void putLong(String k,long v){values.put(k,v);}
    public long getLong(String k){return getLong(k,0);}
    public long getLong(String k,long d){Object v=values.get(k);return v instanceof Number?((Number)v).longValue():d;}
    public void putBundle(String k,Bundle v){values.put(k,v);}
    public Bundle getBundle(String k){return (Bundle)values.get(k);}
    public <T>void putParcelableArrayList(String k,ArrayList<T>v){values.put(k,v);}
    @SuppressWarnings("unchecked") public <T>ArrayList<T>getParcelableArrayList(String k){return (ArrayList<T>)values.get(k);}
    public void putStringArrayList(String k,ArrayList<String>v){values.put(k,v);}
    @SuppressWarnings("unchecked") public ArrayList<String>getStringArrayList(String k){return (ArrayList<String>)values.get(k);}
}
