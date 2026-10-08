package me.brannstroom.expbottle.model;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class WorkTrackerTest {
    @Test void stopAdmissionButDrainExistingCallbacks() {
        WorkTracker work=new WorkTracker();var command=work.enter();work.drain();
        assertNull(work.enter());assertFalse(work.sealIfIdle());
        var callback=work.continuation();command.close();command.close();assertEquals(1,work.outstanding());
        callback.close();assertTrue(work.sealIfIdle());assertNull(work.continuation());assertNull(work.enter());
    }
    @Test void projectileBlocksUnloadUntilImpactOrRemovalExactlyOnce() {
        WorkTracker work=new WorkTracker();UUID id=UUID.randomUUID();work.trackFlight(id);work.trackFlight(id);
        work.drain();assertEquals(1,work.outstanding());assertFalse(work.sealIfIdle());
        work.finishFlight(id);work.finishFlight(id);assertTrue(work.sealIfIdle());
    }
    @Test void restoredFlightStillCountedAfterSealAndRecoveryReopensCommands() {
        WorkTracker work=new WorkTracker();assertTrue(work.sealIfIdle());
        UUID id=UUID.randomUUID();work.trackFlight(id);assertEquals(1,work.outstanding());
        assertFalse(work.sealIfIdle());work.finishFlight(id);assertTrue(work.idle());
        work.resume();try(var command=work.enter()){assertNotNull(command);}
        assertTrue(work.idle());
    }
}
