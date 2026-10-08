package me.brannstroom.expbottle.model;

import me.brannstroom.expbottle.ExpBottle;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.player.PlayerQuitEvent;
import io.papermc.paper.threadedregions.scheduler.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ExperienceTransfersTest {
    @TempDir Path directory;
    ExpBottle plugin;
    ExperienceTransfers transfers;
    MockedStatic<Bukkit> bukkit;
    Player owner;
    Account sender, target;
    int targetMessages, senderMessages;
    Consumer<ScheduledTask> timeout;
    class Account {
        final Player player=mock(Player.class);
        final EntityScheduler scheduler=mock(EntityScheduler.class);
        final List<Runnable> tasks=new ArrayList<>(), retired=new ArrayList<>();
        int level,total;float fraction;
        Account(String name) {
            when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(name.getBytes()));
            when(player.getName()).thenReturn(name);
            when(player.isOnline()).thenReturn(true);
            when(player.locale()).thenReturn(Locale.US);
            when(player.getScheduler()).thenReturn(scheduler);
            when(player.getLevel()).thenAnswer(i->{assertSame(player,owner);return level;});
            when(player.getExp()).thenAnswer(i->{assertSame(player,owner);return fraction;});
            doAnswer(i->{assertSame(player,owner);level=i.getArgument(0);return null;}).when(player).setLevel(anyInt());
            doAnswer(i->{assertSame(player,owner);fraction=i.getArgument(0);return null;}).when(player).setExp(anyFloat());
            doAnswer(i->{assertSame(player,owner);total=i.getArgument(0);return null;}).when(player).setTotalExperience(anyInt());
            when(scheduler.run(eq(plugin),any(),nullable(Runnable.class))).thenAnswer(i->{
                Consumer<ScheduledTask> run=i.getArgument(1);tasks.add(()->run.accept(mock(ScheduledTask.class)));
                Runnable onRetired=i.getArgument(2);if(onRetired!=null)retired.add(onRetired);
                return mock(ScheduledTask.class);
            });
        }
        void set(int xp){owner=player;level=0;fraction=0;Experience.changeExp(player,xp);}
        int xp(){owner=player;return Experience.getExp(player);}
        void run(){owner=player;var copy=List.copyOf(tasks);tasks.clear();copy.forEach(Runnable::run);}
    }
    @BeforeEach void setup() throws Exception {
        plugin=mock(ExpBottle.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("ExpBottleTest"));
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        when(plugin.getWork()).thenReturn(new WorkTracker());
        ExpBottle.instance=plugin;
        bukkit=mockStatic(Bukkit.class);
        bukkit.when(()->Bukkit.isOwnedByCurrentRegion(any(org.bukkit.entity.Entity.class))).thenAnswer(i->i.getArgument(0)==owner);
        GlobalRegionScheduler global=mock(GlobalRegionScheduler.class);
        bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(global);
        when(global.runDelayed(eq(plugin),any(),eq(200L))).thenAnswer(i->{timeout=i.getArgument(1);return mock(ScheduledTask.class);});
        transfers=new ExperienceTransfers(plugin);
        sender=new Account("sender");target=new Account("target");sender.set(1000);target.set(0);
    }
    @AfterEach void cleanup(){transfers.close();bukkit.close();}
    void send(int amount){owner=sender.player;transfers.send(sender.player,target.player,amount,()->{assertSame(target.player,owner);targetMessages++;},()->{assertSame(sender.player,owner);senderMessages++;});}
    @Test void crossOwnerExactlyOnce() {
        send(100);assertEquals(900,sender.xp());target.run();sender.run();
        assertEquals(100,target.xp());assertEquals(900,sender.xp());
        assertEquals(1,targetMessages);assertEquals(1,senderMessages);
        target.retired.forEach(Runnable::run);sender.run();assertEquals(900,sender.xp());
    }
    @Test void duplicateAdmissionCannotDoubleDebit() {
        send(100);send(200);assertEquals(900,sender.xp());target.run();sender.run();assertEquals(100,target.xp());
    }
    @Test void targetCapacityFailureRefundsWithoutMutatingRecipient() {
        target.set(1_500_000_000);sender.set(1_500_000_000);send(1_000_000_000);
        target.run();sender.run();assertEquals(1_500_000_000,sender.xp());assertEquals(1_500_000_000,target.xp());assertEquals(0,targetMessages);
    }
    @Test void schedulerNullRefunds() {
        when(target.scheduler.run(eq(plugin),any(),nullable(Runnable.class))).thenReturn(null);
        send(100);assertEquals(1000,sender.xp());assertEquals(0,target.xp());
    }
    @Test void schedulerThrowsRefunds() {
        when(target.scheduler.run(eq(plugin),any(),nullable(Runnable.class))).thenThrow(new IllegalStateException("test rejection"));
        send(100);assertEquals(1000,sender.xp());
    }
    @Test void recipientRetirementWinsOnce() {
        send(100);owner=target.player;target.retired.getFirst().run();target.retired.getFirst().run();target.run();sender.run();
        assertEquals(1000,sender.xp());assertEquals(0,target.xp());
    }
    @Test void recipientOfflineBeforeCallbackRefunds() {
        send(100);when(target.player.isOnline()).thenReturn(false);target.run();sender.run();assertEquals(1000,sender.xp());
    }
    @Test void timeoutRefundsAndLateDeliveryCannotCredit() {
        send(100);owner=null;timeout.accept(mock(ScheduledTask.class));sender.run();target.run();
        assertEquals(1000,sender.xp());assertEquals(0,target.xp());assertEquals(0,targetMessages);
    }
    @Test void senderQuitBeforeDeliveryCancelsAndRefunds() {
        send(100);owner=sender.player;transfers.onQuit(new PlayerQuitEvent(sender.player,(String)null));target.run();sender.run();
        assertEquals(1000,sender.xp());assertEquals(0,target.xp());
    }
    @Test void senderQuitAfterCreditDoesNotRefund() {
        send(100);target.run();owner=sender.player;transfers.onQuit(new PlayerQuitEvent(sender.player,(String)null));sender.run();
        assertEquals(900,sender.xp());assertEquals(100,target.xp());
    }
    @Test void offlineRefundSurvivesRestartAndClaimsOnce() throws Exception {
        send(100);when(sender.player.isOnline()).thenReturn(false);owner=target.player;target.retired.getFirst().run();sender.run();
        transfers.close();transfers=new ExperienceTransfers(plugin);
        when(sender.player.isOnline()).thenReturn(true);owner=sender.player;transfers.applyRefund(sender.player);transfers.applyRefund(sender.player);
        assertEquals(1000,sender.xp());assertEquals(0,target.xp());
    }
    @Test void orderlyShutdownRetainsQueuedDebit() throws Exception {
        send(100);transfers.close();target.run();assertEquals(0,target.xp());
        transfers=new ExperienceTransfers(plugin);owner=sender.player;transfers.applyRefund(sender.player);assertEquals(1000,sender.xp());
    }
    @Test void fullSenderKeepsRefundUntilSpaceExists() {
        send(100);sender.set(Integer.MAX_VALUE);owner=target.player;target.retired.getFirst().run();sender.run();
        assertEquals(Integer.MAX_VALUE,sender.xp());owner=sender.player;Experience.changeExp(sender.player,-200);transfers.applyRefund(sender.player);
        assertEquals(Integer.MAX_VALUE-100,sender.xp());
    }
    @Test void experienceBoundariesRoundTripAndOverflowRejects() {
        for(int xp:new int[]{0,1,7,315,352,1395,1507,1500000000,Integer.MAX_VALUE}) {sender.set(xp);assertEquals(xp,sender.xp());}
        owner=sender.player;assertThrows(IllegalArgumentException.class,()->Experience.changeExp(sender.player,1));assertEquals(Integer.MAX_VALUE,sender.xp());
        sender.set(0);assertThrows(IllegalArgumentException.class,()->Experience.changeExp(sender.player,-1));assertEquals(0,sender.xp());
    }
    @Test void hotDrainWaitsForDeliveryAndFeedbackBeforeWriterStops() throws Exception {
        send(100);var work=plugin.getWork();work.drain();assertFalse(work.sealIfIdle());
        target.run();assertFalse(work.sealIfIdle());sender.run();assertFalse(transfers.hasPendingTransfers());
        assertTrue(work.sealIfIdle());transfers.prepareClose().get(5,java.util.concurrent.TimeUnit.SECONDS);
        for(int i=0;i<100&&!transfers.writerStopped();i++)Thread.sleep(10);
        assertTrue(transfers.writerStopped());assertEquals(900,sender.xp());assertEquals(100,target.xp());
    }
    @Test void rejectedManagerOperationRestartsRefundWriterAndTransfers() throws Exception {
        transfers.prepareClose().get(5,java.util.concurrent.TimeUnit.SECONDS);
        for(int i=0;i<100 && !transfers.writerStopped();i++) Thread.sleep(10);
        assertTrue(transfers.writerStopped());
        transfers.resumeAfterRejectedClose();send(100);target.run();sender.run();
        assertEquals(900,sender.xp());assertEquals(100,target.xp());
    }
}
