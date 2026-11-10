// SPDX-License-Identifier: GPL-3.0-or-later
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import android.content.Context;
import android.os.Looper;
import android.view.InputDevice;
import android.view.MotionEvent;
import cn.classfun.droidvm.ui.vm.display.base.*;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.*;

public class StandardCaptureProbe {
 static StandardPointerCapture capture;
 static List<String> events=Collections.synchronizedList(new ArrayList<>());
 static int checks;
 static void set(String name,Object value) throws Exception {
  Field f=StandardPointerCapture.class.getDeclaredField(name);f.setAccessible(true);f.set(capture,value);
 }
 static void flush() throws Exception {
  Field f=StandardPointerCapture.class.getDeclaredField("worker");f.setAccessible(true);
  ((ExecutorService)f.get(capture)).submit(()->{}).get(5,TimeUnit.SECONDS);
 }
 static void expect(String label,String...expected) throws Exception {
  flush();if(!events.equals(Arrays.asList(expected))) throw new AssertionError(fmt("%s %s expected %s", label, events, Arrays.toString(expected)));
  events.clear();checks++;System.out.println(fmt("PASS %s", label));
 }
 static MotionEvent event(int device,int source,int action,int buttons,int[] ids,float[] x,float[] y,float wheel) {
  MotionEvent.PointerProperties[] p=new MotionEvent.PointerProperties[ids.length];
  MotionEvent.PointerCoords[] c=new MotionEvent.PointerCoords[ids.length];
  for(int i=0;i<ids.length;i++) {
   p[i]=new MotionEvent.PointerProperties();p[i].id=ids[i];p[i].toolType=MotionEvent.TOOL_TYPE_FINGER;
   c[i]=new MotionEvent.PointerCoords();c[i].x=x[i];c[i].y=y[i];c[i].pressure=1;c[i].setAxisValue(MotionEvent.AXIS_VSCROLL,wheel);
  }
  return MotionEvent.obtain(100,180,action,ids.length,p,c,0,buttons,1,1,device,0,source,0);
 }
 static void relative(int device,int buttons,float dx,float dy,float wheel) {
  MotionEvent e=event(device,InputDevice.SOURCE_MOUSE_RELATIVE,wheel==0?MotionEvent.ACTION_MOVE:MotionEvent.ACTION_SCROLL,buttons,new int[]{0},new float[]{dx},new float[]{dy},wheel);
  if(!capture.onEvent(e))throw new AssertionError("unhandled relative");e.recycle();
 }
 static void raw(int action,int buttons,int[] ids,float[] x,float[] y) {
  MotionEvent e=event(990,InputDevice.SOURCE_TOUCHPAD,action,buttons,ids,x,y,0);
  if(!capture.onEvent(e))throw new AssertionError("unhandled contacts");e.recycle();
 }
 static void staleButtonEvents(int source, int device) throws Exception {
  var setActionButton = MotionEvent.class.getMethod("setActionButton", int.class);
  int secondary = MotionEvent.BUTTON_SECONDARY;
  for (boolean pressed : new boolean[]{true, false}) {
   int action = pressed ? MotionEvent.ACTION_BUTTON_PRESS : MotionEvent.ACTION_BUTTON_RELEASE;
   MotionEvent e = event(device, source, action, pressed ? 0 : secondary,
       new int[]{0}, new float[]{0}, new float[]{0}, 0);
   try {
    setActionButton.invoke(e, secondary);
    if (!capture.onEvent(e)) throw new AssertionError("unhandled button event");
   } finally {
    e.recycle();
   }
  }
 }
 public static void main(String[]args) throws Exception {
  if(Looper.myLooper()==null)Looper.prepareMainLooper();
  Class<?> at=Class.forName("android.app.ActivityThread");
  Object thread=at.getMethod("systemMain").invoke(null);
  Context context=(Context)at.getMethod("getSystemContext").invoke(thread);
  capture=new StandardPointerCapture(new CapturedPointerLayout(context,null));
  capture.setSink((channel,data)->{
   if(channel!=2)throw new AssertionError("wrong channel");
   ByteBuffer b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
   while(b.remaining()>=8){int type=b.getShort()&65535,code=b.getShort()&65535,value=b.getInt();
    if(type!=0)events.add(fmt("%d:%d:%d", type, code, value));
   }return true;
  });
  set("active",true);set("natural",true);set("systemNatural",true);
  try {
   relative(900,1,0,0,0);expect("primary press","1:272:1");
   relative(901,0,0,0,0);expect("idle second device preserves held primary");
   capture.onInputDeviceRemoved(900);expect("disconnect releases held primary","1:272:0");
   staleButtonEvents(InputDevice.SOURCE_MOUSE_RELATIVE, 906);
   expect("relative action button overrides stale state", "1:273:1", "1:273:0");
   staleButtonEvents(InputDevice.SOURCE_TOUCHPAD, 907);
   expect("raw action button overrides stale state", "1:273:1", "1:273:0");
   MotionEvent history=event(902,InputDevice.SOURCE_MOUSE_RELATIVE,MotionEvent.ACTION_MOVE,0,new int[]{0},new float[]{1.25f},new float[]{-0.5f},0);
   MotionEvent.PointerCoords point=new MotionEvent.PointerCoords();point.x=.75f;point.y=-.5f;
   history.addBatch(190,new MotionEvent.PointerCoords[]{point},0);capture.onEvent(history);history.recycle();
   expect("history and fractional motion retained","2:0:2","2:1:-1");
   relative(903,0,0,0,.6f);relative(904,0,0,0,.6f);expect("wheel remainders isolated");
   relative(903,0,0,0,.6f);expect("fractional wheel accumulates","2:8:1");
   raw(MotionEvent.ACTION_DOWN,0,new int[]{4},new float[]{10},new float[]{10});
   raw(MotionEvent.ACTION_UP,0,new int[]{4},new float[]{10},new float[]{10});
   expect("Android UP includes the last pointer","1:272:1","1:272:0");
   raw(MotionEvent.ACTION_DOWN,0,new int[]{4},new float[]{10},new float[]{10});
   raw(MotionEvent.ACTION_POINTER_DOWN|(1<<8),0,new int[]{4,8},new float[]{10,20},new float[]{10,20});
   raw(MotionEvent.ACTION_MOVE,0,new int[]{8,4},new float[]{20,10},new float[]{20,10});
   raw(MotionEvent.ACTION_POINTER_UP|(1<<8),0,new int[]{8,4},new float[]{20,10},new float[]{20,10});
   raw(MotionEvent.ACTION_UP,0,new int[]{8},new float[]{20},new float[]{20});
   expect("staggered/reordered two-finger tap","1:273:1","1:273:0");
   raw(MotionEvent.ACTION_BUTTON_PRESS,1,new int[]{0},new float[]{0},new float[]{0});
   raw(MotionEvent.ACTION_BUTTON_RELEASE,0,new int[]{0},new float[]{0},new float[]{0});
   expect("button-only event does not invent contacts","1:272:1","1:272:0");
   raw(MotionEvent.ACTION_DOWN,1,new int[]{4},new float[]{10},new float[]{10});
   raw(MotionEvent.ACTION_CANCEL,0,new int[]{4},new float[]{10},new float[]{10});
   expect("cancel releases raw drag without tap","1:272:1","1:272:0");
   int touchpad=-1;
   for(int id:InputDevice.getDeviceIds()){InputDevice d=InputDevice.getDevice(id);if(d!=null&&d.supportsSource(InputDevice.SOURCE_TOUCHPAD)){touchpad=id;break;}}
   if(touchpad<0)throw new AssertionError("physical touchpad missing");
   relative(touchpad,0,0,0,16);
   expect("half a touchpad notch stays buffered");
   relative(touchpad,0,0,0,16);
   expect("two half touchpad notches accumulate","2:8:1");
   set("natural",false);relative(touchpad,0,0,0,32);
   expect("relative touchpad direction preference","2:8:-1");
   relative(905,0,0,0,1);expect("mouse wheel unaffected by touchpad preference","2:8:1");
  } finally {capture.close();}
  System.out.println(fmt("ALL %d Android adapter checks passed", checks));
 }
}
