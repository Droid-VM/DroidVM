// SPDX-License-Identifier: GPL-3.0-or-later
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import android.content.Context;
import android.os.Looper;
import android.view.InputDevice;
import android.view.MotionEvent;
import cn.classfun.droidvm.ui.vm.display.base.*;
import cn.classfun.droidvm.ui.vm.display.nativedisplay.input.DirectInputSink;
import java.lang.reflect.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;

public class CaptureRegressionProbe {
 static Field field(String name)throws Exception {Field f=StandardPointerCapture.class.getDeclaredField(name);f.setAccessible(true);return f;}
 static void lifecycle()throws Exception {
  if(Looper.myLooper()==null)Looper.prepareMainLooper();
  Class<?> at=Class.forName("android.app.ActivityThread");Object thread=at.getMethod("systemMain").invoke(null);
  Context context=(Context)at.getMethod("getSystemContext").invoke(thread);
  List<Integer> guest=new ArrayList<>();
  DirectInputSink sink=new DirectInputSink("vm",()->"gpu-0",null,(channel,data)->{
   ByteBuffer b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
   while(b.remaining()>=8){int type=b.getShort(),code=b.getShort(),value=b.getInt();if(type==1&&code==272)guest.add(value);}return true;
  });
  StandardPointerCapture capture=new StandardPointerCapture(new CapturedPointerLayout(context,null));
  capture.setSink(sink);field("active").set(capture,true);
  MotionEvent.PointerProperties prop=new MotionEvent.PointerProperties();prop.id=0;prop.toolType=MotionEvent.TOOL_TYPE_MOUSE;
  MotionEvent.PointerCoords coord=new MotionEvent.PointerCoords();
  MotionEvent event=MotionEvent.obtain(1,1,MotionEvent.ACTION_BUTTON_PRESS,1,new MotionEvent.PointerProperties[]{prop},new MotionEvent.PointerCoords[]{coord},0,1,1,1,999,0,InputDevice.SOURCE_MOUSE_RELATIVE,0);
  capture.onEvent(event);event.recycle();
  ExecutorService worker=(ExecutorService)field("worker").get(capture);
  worker.submit(()->{}).get(3,TimeUnit.SECONDS);
  CountDownLatch entered=new CountDownLatch(1),resume=new CountDownLatch(1);
  worker.submit(()->{entered.countDown();try{resume.await();}catch(InterruptedException e){throw new RuntimeException(e);}});
  if(!entered.await(3,TimeUnit.SECONDS))throw new AssertionError("worker not paused");
  capture.close(sink::close);resume.countDown();
  if(!worker.awaitTermination(3,TimeUnit.SECONDS))throw new AssertionError("worker not drained");
  System.out.println(fmt("Lifecycle actual guest button events=%s expected=[1, 0]", guest));
  if(!guest.equals(List.of(1,0)))throw new AssertionError("release was dropped");
  if(sink.write(2,new byte[0]))throw new AssertionError("sink was not closed after drain");
 }
 static void wheel(){
  ArrayDeque<Runnable> work=new ArrayDeque<>();int[] position={0};
  PointerEventQueue q=new PointerEventQueue(work::addLast,new PointerEventQueue.Output(){
   public void move(int x,int y){}public void button(int code,boolean down){}
   public void scroll(int v,int h){position[0]=Math.max(0,position[0]-v);}
  });
  q.scroll(1,0);q.scroll(-1,0);while(!work.isEmpty())work.removeFirst().run();
  System.out.println(fmt("Wheel top-boundary position=%d expected=1", position[0]));
  if(position[0]!=1)throw new AssertionError("wheel reversal was lost");
 }
 static void chord(){
  boolean[] held={false};List<String> raw=new ArrayList<>();
  TouchpadGestureRecognizer pad=new TouchpadGestureRecognizer(new PointerEventQueue.Output(){
   public void move(int x,int y){}public void scroll(int v,int h){}
   public void button(int code,boolean down){raw.add(fmt("%d:%s", code, down));if(code==273)held[0]=down;}
  },true);
  pad.frame(3,10,10,100,1);pad.setButtons(3);pad.setButtons(2);
  System.out.println(fmt("Raw chord secondary held=%s expected=true events=%s", held[0], raw));
  if(!held[0])throw new AssertionError("secondary released while still held");
  pad.setButtons(0);if(held[0])throw new AssertionError("final secondary release missing");
  if(!raw.equals(List.of("273:true","273:false")))throw new AssertionError(fmt("duplicate transitions %s", raw));
 }
 public static void main(String[]args)throws Exception {lifecycle();wheel();chord();System.out.println("ALL 3 merge-review regression checks passed");}
}
