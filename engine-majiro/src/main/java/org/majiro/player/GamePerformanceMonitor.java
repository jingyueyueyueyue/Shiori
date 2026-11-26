package org.majiro.player;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.json.JSONObject;
import org.galplayer.performance.FrameRateSampler;
import org.galplayer.performance.GpuUsage;
import org.galplayer.performance.GpuUsageStatus;
import org.galplayer.performance.SystemPerformanceSampler;
import org.galplayer.performance.SystemPerformanceSnapshot;

final class GamePerformanceMonitor {
    private static final String EXTRA_HUD_ENABLED="extra_hud_enabled";
    private final Activity activity;
    private final FrameLayout root;
    private final SharedPreferences preferences;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final HandlerThread samplingThread=new HandlerThread("Majiro performance");
    private final Handler sampling;
    private final FrameRateSampler frameRate=new FrameRateSampler(2_000_000_000L);
    private final int margin;
    private final int touchSlop;
    private TextView view;
    private SystemPerformanceSampler sampler;
    private volatile boolean samplingActive;
    private volatile long samplingGeneration;
    private boolean enabled,resumed=true,closed,dragging;
    private float fractionX=.5f,fractionY;
    private float downX,downY,startX,startY;

    GamePerformanceMonitor(Activity activity,FrameLayout root){
        this.activity=activity;this.root=root;
        preferences=activity.getSharedPreferences("performance_overlay",Activity.MODE_PRIVATE);
        margin=Ui.dp(activity,8);touchSlop=ViewConfiguration.get(activity).getScaledTouchSlop();
        float x=preferences.getFloat("position_x",Float.NaN),y=preferences.getFloat("position_y",Float.NaN);
        if(x>=0&&x<=1&&y>=0&&y<=1){fractionX=x;fractionY=y;}
        enabled=activity.getIntent().hasExtra(EXTRA_HUD_ENABLED)
            ?activity.getIntent().getBooleanExtra(EXTRA_HUD_ENABLED,false)
            :preferences.getBoolean("enabled",false);
        samplingThread.start();sampling=new Handler(samplingThread.getLooper());
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->place());
        if(enabled)show();
    }

    boolean isEnabled(){return enabled;}
    void toggle(){setEnabled(!enabled);}
    private void setEnabled(boolean value){
        if(closed||enabled==value)return;
        enabled=value;preferences.edit().putBoolean("enabled",value).apply();
        if(value)show();else hide();
    }
    private void show(){
        if(closed)return;
        if(view==null){
            view=new TextView(activity);
            view.setTextSize(12);view.setTextColor(Color.WHITE);view.setTypeface(Typeface.MONOSPACE);
            view.setSingleLine();view.setPadding(Ui.dp(activity,10),Ui.dp(activity,6),Ui.dp(activity,10),Ui.dp(activity,6));
            view.setElevation(Ui.dp(activity,7));
            view.setOnTouchListener(this::touch);
            view.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->place());
            FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.START);
            params.setMargins(margin,margin,margin,margin);
            root.addView(view,0,params);updateBackground();
        }
        render(null,null);place();
        if(resumed)startSampling();
    }
    private void hide(){
        stopSampling();
        if(view!=null){root.removeView(view);view=null;}
    }
    void pause(){
        resumed=false;stopSampling();
    }
    void resume(){
        if(closed)return;
        resumed=true;
        if(enabled)startSampling();
    }
    void close(){
        if(closed)return;
        closed=true;hide();samplingThread.quitSafely();
    }

    private void startSampling(){
        if(closed||!resumed||!enabled||samplingActive)return;
        samplingActive=true;
        final long generation=++samplingGeneration;
        sampling.removeCallbacksAndMessages(null);
        sampling.post(()->{
            if(!isSampling(generation))return;
            sampler=new SystemPerformanceSampler(activity.getApplicationContext());
            frameRate.reset();sample(generation);
        });
    }
    private void stopSampling(){
        samplingActive=false;++samplingGeneration;
        sampling.removeCallbacksAndMessages(null);
    }
    private boolean isSampling(long generation){
        return !closed&&resumed&&enabled&&samplingActive&&samplingGeneration==generation;
    }
    private void sample(long generation){
        if(!isSampling(generation))return;
        SystemPerformanceSnapshot metrics=sampler.sample();
        Float fps=frameRate.sample(System.nanoTime(),readPresentedFrames());
        ui.post(()->{if(isSampling(generation))render(metrics,fps);});
        sampling.postDelayed(()->sample(generation),1000);
    }

    private Long readPresentedFrames(){
        String name=switch(activity.getPackageName()){
            case "org.majiro.player"->"majiro-debug";
            case "org.majiro.player.recovery"->"majiro-debug-recovery";
            default->"majiro-debug-"+activity.getPackageName();
        };
        try(LocalSocket socket=new LocalSocket()){
            socket.connect(new LocalSocketAddress(name,LocalSocketAddress.Namespace.ABSTRACT));
            socket.setSoTimeout(750);
            OutputStream output=socket.getOutputStream();
            output.write("RENDER_STATS\n".getBytes(StandardCharsets.US_ASCII));output.flush();
            InputStream input=socket.getInputStream();
            ByteArrayOutputStream response=new ByteArrayOutputStream();
            byte[] buffer=new byte[4096];
            while(response.size()<=65536){
                int count=input.read(buffer);
                if(count<0)return null;
                for(int index=0;index<count;++index){
                    if(buffer[index]=='\n'){
                        response.write(buffer,0,index);
                        JSONObject stats=new JSONObject(new String(response.toByteArray(),StandardCharsets.UTF_8));
                        return stats.has("presented")?stats.getLong("presented"):null;
                    }
                }
                response.write(buffer,0,count);
            }
            return null;
        }catch(Exception unavailable){return null;}
    }

    private void render(SystemPerformanceSnapshot metrics,Float fps){
        if(view==null)return;
        String frames=fps==null?"  —":String.format(Locale.ROOT,"%3.0f",fps);
        String gpu="  —",cpu="  —",ram="  —",power="  — W";
        if(metrics!=null){
            GpuUsage usage=metrics.getGpu();
            if(usage.getStatus()==GpuUsageStatus.AVAILABLE||usage.getStatus()==GpuUsageStatus.STALE){
                gpu=percent(usage.getPercent());
                if(usage.getStatus()==GpuUsageStatus.STALE)gpu="~"+gpu.trim();
            }
            cpu=percent(metrics.getCpuPercent());ram=percent(metrics.getRamPercent());
            if(metrics.getExternalPower())power="外接电源";
            else if(metrics.getPowerWatts()!=null)power=String.format(Locale.ROOT,"%.1f W",metrics.getPowerWatts());
        }
        String text="FPS "+frames+"   GPU "+gpu+"   CPU "+cpu+"   RAM "+ram+"   PWR "+power;
        view.setText(text);view.setContentDescription("性能监控，"+text+"。可拖动浮窗。");
    }
    private static String percent(Float value){
        return value==null?"  —":String.format(Locale.ROOT,"%.0f%%",value);
    }

    private boolean touch(View owner,MotionEvent event){
        switch(event.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                downX=event.getRawX();downY=event.getRawY();startX=owner.getTranslationX();startY=owner.getTranslationY();
                dragging=false;return true;
            case MotionEvent.ACTION_MOVE:
                float dx=event.getRawX()-downX,dy=event.getRawY()-downY;
                if(!dragging&&Math.hypot(dx,dy)>touchSlop){dragging=true;updateBackground();}
                if(dragging){
                    float width=travel(true),height=travel(false);
                    float direction=root.getLayoutDirection()==View.LAYOUT_DIRECTION_RTL?-1f:1f;
                    if(width>0)fractionX=Math.max(0,Math.min(1,(startX+dx)*direction/width));
                    if(height>0)fractionY=Math.max(0,Math.min(1,(startY+dy)/height));
                    place();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if(dragging)preferences.edit().putFloat("position_x",fractionX).putFloat("position_y",fractionY).apply();
                else if(event.getActionMasked()==MotionEvent.ACTION_UP)owner.performClick();
                dragging=false;updateBackground();return true;
            default:return true;
        }
    }
    private float travel(boolean horizontal){
        if(view==null)return 0;
        int size=horizontal?view.getWidth():view.getHeight();
        int available=horizontal?root.getWidth()-root.getPaddingLeft()-root.getPaddingRight()
            :root.getHeight()-root.getPaddingTop()-root.getPaddingBottom();
        return Math.max(0,available-size-2*margin);
    }
    private void place(){
        if(view==null)return;
        float direction=root.getLayoutDirection()==View.LAYOUT_DIRECTION_RTL?-1f:1f;
        view.setTranslationX(travel(true)*fractionX*direction);view.setTranslationY(travel(false)*fractionY);
    }
    private void updateBackground(){
        if(view==null)return;
        GradientDrawable background=new GradientDrawable();
        background.setColor(dragging?0xdb000000:0xb8000000);background.setCornerRadius(Ui.dp(activity,4));
        if(dragging)background.setStroke(Ui.dp(activity,1),0xff5096f3);
        view.setBackground(background);
    }
}
