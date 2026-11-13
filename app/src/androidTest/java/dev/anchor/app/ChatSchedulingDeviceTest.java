package dev.anchor.app;
import android.test.AndroidTestCase;
import java.time.*;
import java.util.*;
public class ChatSchedulingDeviceTest extends AndroidTestCase {
    private AnchorDb db;
    @Override protected void setUp() throws Exception {super.setUp();getContext().deleteDatabase("chat-scheduling-test.db");db=new AnchorDb(getContext(),"chat-scheduling-test.db");}
    @Override protected void tearDown() throws Exception {db.close();getContext().deleteDatabase("chat-scheduling-test.db");super.tearDown();}
    private Task task(){Task t=new Task();t.title="Chat schedule validation";t.date=LocalDate.now().plusDays(2).toString();t.time="08:00";t.durationMinutes=30;return t;}
    public void testCreationPersistsAndReportsActualCalendarSuccess() {
        ChatMessage message=db.scheduleFromChat(task());assertTrue(message.taskId>0);
        db.close();db=new AnchorDb(getContext(),"chat-scheduling-test.db");
        Task saved=db.task(message.taskId);assertNotNull(saved);assertEquals("08:00",saved.time);assertEquals(30,saved.durationMinutes);
        ChatMessage stored=db.messages().get(0);assertEquals(message.taskId,stored.taskId);assertTrue(stored.canUndoCreation);assertTrue(stored.content.contains("Scheduled"));
    }
    public void testRepeatedRequestDoesNotDuplicateTheEntry() {
        db.scheduleFromChat(task());ChatMessage repeated=db.scheduleFromChat(task());
        assertEquals(1,db.tasks(task().date).size());assertEquals(0,repeated.taskId);assertTrue(repeated.content.contains("Already"));
    }
    public void testConflictingProtectedCommitmentIsNotMovedOrOverbooked() {
        Task fixed=task();fixed.title="Protected commitment";fixed.event=true;db.save(fixed);
        try{db.scheduleFromChat(task());fail("A conflict must be explained, never silently saved");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("occupied"));}
        assertEquals(1,db.tasks(fixed.date).size());assertEquals("08:00",db.task(fixed.id).time);assertTrue(db.messages().isEmpty());
    }
    public void testCreationUndoIsDurableAndDoesNotDeleteLaterEdits() {
        ChatMessage created=db.scheduleFromChat(task());assertTrue(db.undoChatCreation(created.id));assertNull(db.task(created.taskId));assertTrue(db.messages().get(0).creationUndone);
        ChatMessage next=db.scheduleFromChat(task());Task edited=db.task(next.taskId);edited.title="Edited by user";db.save(edited);
        assertFalse(db.undoChatCreation(next.id));assertNotNull(db.task(next.taskId));
    }
    public void testVersionTwoUpgradePreservesChatAndCalendar() {
        Task old=task();db.save(old);
        android.database.sqlite.SQLiteDatabase sql=db.getWritableDatabase();
        sql.execSQL("ALTER TABLE messages RENAME TO newer_messages");
        sql.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT,role TEXT NOT NULL,content TEXT NOT NULL,created_at INTEGER NOT NULL)");
        sql.execSQL("INSERT INTO messages(role,content,created_at) VALUES('user','Earlier conversation',1)");
        sql.execSQL("DROP TABLE newer_messages");sql.execSQL("DROP TABLE period_plan_routines");sql.execSQL("DROP TABLE period_plans");sql.execSQL("DROP TABLE routine_entries");sql.execSQL("DROP TABLE routines");sql.setVersion(2);db.close();
        db=new AnchorDb(getContext(),"chat-scheduling-test.db");
        assertEquals("Earlier conversation",db.messages().get(0).content);assertEquals(0,db.messages().get(0).taskId);
        assertEquals(old.title,db.task(old.id).title);assertEquals(6,db.getReadableDatabase().getVersion());
    }
}
