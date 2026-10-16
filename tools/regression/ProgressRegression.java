import com.deepseek.neoforbric.core.*;
import java.lang.reflect.*;
import java.util.Map;
import net.neoforged.fml.loading.progress.ProgressMeter;

public final class ProgressRegression {
    static int pass,fail;
    static void check(String label,boolean ok){System.out.println((ok?"PASS ":"FAIL ")+label);if(ok)pass++;else fail++;}
    static void check(String label,boolean ok,String detail){System.out.println((ok?"PASS ":"FAIL ")+label+" -> "+detail);if(ok)pass++;else fail++;}
    static DownloadEvent event(String stage,String task,String label,int attempt,long received,long total,DownloadEvent.State state){
        return new DownloadEvent(stage,task,label,attempt,received,total,state,"");
    }
    static String labels(Map<String,ProgressMeter> rows){
        StringBuilder sb=new StringBuilder();
        for(Map.Entry<String,ProgressMeter> e:rows.entrySet()){sb.append(e.getKey()).append('=').append(e.getValue().label().getText()).append(" | ");}
        return sb.toString();
    }
    public static void main(String[] args)throws Exception{
        Class<?> type=Class.forName("com.deepseek.neoforbric.early.NeoForbricLocator$ScreenProgress");
        Constructor<?> constructor=type.getDeclaredConstructor();constructor.setAccessible(true);
        ProgressReporter reporter=(ProgressReporter)constructor.newInstance();
        ProgressStage stage=new ProgressStage(reporter,"review","Review",2);
        Field field=type.getDeclaredField("meter");field.setAccessible(true);
        ProgressMeter meter=(ProgressMeter)field.get(reporter);
        stage.done("a","installed");stage.failed("a","late failure for installed task");
        check("terminal-task-counted-once",stage.finished()==1&&meter.progress()==0.5f);
        stage.kept("b","kept original");check("kept-completes-phase",meter.progress()==1.0f);
        stage.end();check("end-does-not-drop-kept",meter.progress()==1.0f&&stage.finished()==2);
        ProgressStage next=new ProgressStage(reporter,"review","Another instance",2);
        check("stage-instance-id-is-unique",!next.id().equals(stage.id()));
        reporter.downloadEvent(new DownloadEvent(stage.id(),"file","late",1,8192,8192,DownloadEvent.State.PROGRESS,""));
        Field eventsField=type.getDeclaredField("downloads");eventsField.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String,DownloadEvent> events=(Map<String,DownloadEvent>)eventsField.get(reporter);
        check("late-old-instance-event-rejected",events.isEmpty()&&meter.progress()==0.0f);
        next.downloadEvent(new DownloadEvent(next.id(),"file","sample",1,400,1000,DownloadEvent.State.PROGRESS,""));
        check("byte-event-does-not-complete-task",next.finished()==0&&meter.progress()==0.0f);
        next.downloadEvent(new DownloadEvent(next.id(),"file","sample",1,200,1000,DownloadEvent.State.PROGRESS,""));
        check("same-attempt-does-not-go-backwards",events.get("file").received()==400);
        next.downloadEvent(new DownloadEvent(next.id(),"file","sample",2,0,1000,DownloadEvent.State.STARTED,""));
        check("retry-can-reset-byte-counter",events.get("file").attempt()==2&&events.get("file").received()==0);
        next.downloadEvent(new DownloadEvent(next.id(),"file","sample",1,900,1000,DownloadEvent.State.PROGRESS,""));
        check("old-attempt-event-rejected",events.get("file").attempt()==2&&events.get("file").received()==0);
        next.downloadEvent(new DownloadEvent(next.id(),"file","sample",2,1000,1000,DownloadEvent.State.DONE,""));
        next.downloadEvent(new DownloadEvent(next.id(),"file","sample",2,500,1000,DownloadEvent.State.PROGRESS,""));
        check("completed-download-stays-completed",events.get("file").state()==DownloadEvent.State.DONE);
        next.downloadEvent(new DownloadEvent(next.id(),"unknown","unknown",1,512,-1,DownloadEvent.State.PROGRESS,""));
        check("unknown-size-has-no-fake-fraction",events.get("unknown").fraction()==-1);
        next.done("connector","Connector ready");next.failed("ffapi","FFAPI unavailable");next.end();
        check("two-support-terminals-complete-phase",meter.progress()==1&&next.finished()==2);
        reporter.finishAll();
        perFileRows(type);
        System.out.println("PROGRESS_REGRESSION PASSED="+pass+" FAILED="+fail);
        if(fail>0)System.exit(1);
    }

    /**
     * C10-01/C10-02: the per-file rows carry the percentage first, the stage counts in every row, at
     * most two rows, and an immediate cleanup on every terminal state.
     */
    static void perFileRows(Class<?> type)throws Exception{
        Constructor<?> ctor=type.getDeclaredConstructor(boolean.class);ctor.setAccessible(true);
        ProgressReporter shown=(ProgressReporter)ctor.newInstance(true);
        Method fileText=type.getDeclaredMethod("fileText",DownloadEvent.class,int.class);fileText.setAccessible(true);
        Field metersField=type.getDeclaredField("fileMeters");metersField.setAccessible(true);
        Field activeField=type.getDeclaredField("activeFiles");activeField.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String,ProgressMeter> rows=(Map<String,ProgressMeter>)metersField.get(shown);
        @SuppressWarnings("unchecked") Map<String,DownloadEvent> active=(Map<String,DownloadEvent>)activeField.get(shown);
        String stage="NeoForbric: Translating";
        shown.stageBegin("d1",stage,6);
        shown.stageState("d1",stage,5,0,0,0,6);
        String known=(String)fileText.invoke(shown,event("d1","k","sample.jar",1,400,1000,DownloadEvent.State.PROGRESS),0);
        check("row-percent-comes-first",known.startsWith("40% "),known);
        check("row-carries-the-stage-counts",known.contains(" 5/6 "),known);
        check("row-has-no-phase-prefix",!known.contains("Translating")&&!known.contains("NeoForbric"),known);
        check("row-length-is-bounded",known.length()<=46,"len="+known.length()+" "+known);
        String unknown=(String)fileText.invoke(shown,event("d1","u","sample.jar",1,1200000,-1,DownloadEvent.State.PROGRESS),0);
        check("row-unknown-size-never-fakes-a-percent",unknown.startsWith("~1.1M ")&&!unknown.contains("%"),unknown);
        check("row-unknown-size-still-carries-the-counts",unknown.contains(" 5/6 ")&&unknown.length()<=46,unknown);
        String longRow=(String)fileText.invoke(shown,event("d1","l","a".repeat(80)+".jar",2,400,1000,DownloadEvent.State.PROGRESS),0);
        check("row-long-name-is-elided-in-the-middle",longRow.contains("..")&&longRow.length()<=46,longRow);
        check("row-long-name-stays-ascii",longRow.chars().allMatch(c->c<128),longRow);
        check("row-retry-shows-the-attempt-number",longRow.contains(" #2"),longRow);
        String hiddenRow=(String)fileText.invoke(shown,event("d1","h","sample.jar",1,400,1000,DownloadEvent.State.PROGRESS),2);
        check("row-hidden-count-is-appended",hiddenRow.endsWith(" +2")&&hiddenRow.length()<=46,hiddenRow);
        check("row-zero-hidden-count-is-not-appended",!known.contains(" +"),known);

        shown.finishAll();
        shown.stageBegin("d2",stage,3);
        shown.downloadEvent(event("d2","f1","one.jar",1,100,1000,DownloadEvent.State.PROGRESS));
        shown.downloadEvent(event("d2","f2","two.jar",1,100,1000,DownloadEvent.State.PROGRESS));
        check("one-row-per-active-file",rows.size()==2,labels(rows));
        shown.downloadEvent(event("d2","f3","three.jar",1,100,1000,DownloadEvent.State.PROGRESS));
        check("at-most-two-file-rows",rows.size()==2&&active.size()==3,labels(rows));
        check("the-earliest-two-files-keep-their-rows",rows.containsKey("f1")&&rows.containsKey("f2"),labels(rows));
        check("every-visible-row-counts-the-hidden-ones",
                rows.values().stream().allMatch(row->row.label().getText().endsWith(" +1")),labels(rows));
        shown.downloadEvent(event("d2","f1","one.jar",1,1000,1000,DownloadEvent.State.DONE));
        check("a-done-file-loses-its-row-immediately",!rows.containsKey("f1")&&rows.size()==2,labels(rows));
        shown.downloadEvent(event("d2","f2","two.jar",1,1000,1000,DownloadEvent.State.FAILED));
        check("a-failed-file-loses-its-row-immediately",
                !rows.containsKey("f2")&&rows.size()==1&&rows.containsKey("f3"),labels(rows));
        shown.downloadEvent(event("d2","f3","three.jar",1,1000,1000,DownloadEvent.State.SKIPPED));
        check("a-skipped-file-loses-its-row-immediately",rows.isEmpty(),labels(rows));
        shown.downloadEvent(event("d2","f4","four.jar",1,100,1000,DownloadEvent.State.PROGRESS));
        shown.stageEnd("d2",stage,3,0,0,0);
        check("stage-end-clears-every-row",rows.isEmpty()&&active.isEmpty(),labels(rows));
        shown.stageBegin("d3",stage,2);
        shown.downloadEvent(event("d3","f5","five.jar",1,100,1000,DownloadEvent.State.PROGRESS));
        shown.finishAll();
        check("finish-all-clears-every-row",rows.isEmpty()&&active.isEmpty(),labels(rows));
    }
}
