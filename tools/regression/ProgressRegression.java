import com.deepseek.neoforbric.core.*;
import java.lang.reflect.*;
import java.util.Map;
import net.neoforged.fml.loading.progress.ProgressMeter;

public final class ProgressRegression {
    static int pass,fail;
    static void check(String label,boolean ok){System.out.println((ok?"PASS ":"FAIL ")+label);if(ok)pass++;else fail++;}
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
        System.out.println("PROGRESS_REGRESSION PASSED="+pass+" FAILED="+fail);
        if(fail>0)System.exit(1);
    }
}
