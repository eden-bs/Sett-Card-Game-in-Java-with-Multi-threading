package bguspl.set.ex;

import bguspl.set.Env;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    public final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Dealer dealer;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    protected int score;

    protected ArrayList<Integer> tokensPlaced;

    protected ArrayBlockingQueue<Integer> keyPressQueue;

    protected Object playerLock;

    protected volatile boolean blockInput = false;

    /**
     * The current status of the player: 0=normal, 1=point, 2=penalty
     */
    protected volatile int currentStatus = 0;

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        tokensPlaced = new ArrayList<>();
        keyPressQueue = new ArrayBlockingQueue<>(env.config.featureSize);
        playerLock = new Object();
        this.dealer = dealer;
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("Thread " + Thread.currentThread().getName() + " starting.");
        if (!human) createArtificialIntelligence();

        while (!terminate) {
            handleCurrentStatus();
            consumeKeyPressQueue();
        }
        if (!human)
//            while (aiThread.isAlive())
                try {
                    aiThread.join();
                } catch (InterruptedException ignored) {
                }
        env.logger.info("Thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.info("Thread " + Thread.currentThread().getName() + " starting.");
            Random random = new Random();
            while (!terminate) {
                keyPressed(random.nextInt(env.config.tableSize));
//                try {
//                    aiThread.sleep(500);
//                } catch (InterruptedException e) {
//
//                }

            }
            env.logger.info("Thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        terminate = true;
    }

    public void wakePlayer() {
        synchronized (playerLock) {
            playerLock.notifyAll();
        }
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        if (table.slotToCard[slot] != null && keyPressQueue.size() < env.config.featureSize && currentStatus == 0 && table.isTableReady)
            keyPressQueue.add(slot);
    }

    public void consumeKeyPressQueue() {
        if (!keyPressQueue.isEmpty()) {
            Integer slotToPlace = keyPressQueue.poll();
            if (slotToPlace == null) {
                return;
            }
            updateSet(slotToPlace);
            if (tokensPlaced.size() == env.config.featureSize && currentStatus != 2) {
                synchronized (table.submittedSets) {
                    table.submittedSets.add(this.id);
                }
                try {
                    synchronized (playerLock) {
                        dealer.wakeDealer();
                        playerLock.wait();
                    }
                } catch (InterruptedException e) {
                }
            }
        }
    }


    private void updateSet(int slot) {
        synchronized (table) {
            for (int i = 0; i < tokensPlaced.size(); i++) {
                if (tokensPlaced.get(i) == slot) {
                    tokensPlaced.remove(i);
                    table.removeToken(this.id, slot);
                    currentStatus = 0;
                    blockInput = false;
                    return;
                }
            }

            if (tokensPlaced.size() < env.config.featureSize) {
                tokensPlaced.add(slot);
                table.placeToken(this.id, slot);
                if (tokensPlaced.size() == env.config.featureSize) {
                    blockInput = true;
                }
            }
        }
    }


    private void handleCurrentStatus() {
        if (currentStatus == 1) {
            point();
        }
        if (currentStatus == 2)
            penalty();
        currentStatus = 0;
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        int ignored = table.countCards();
        env.ui.setScore(id, ++score);
        int numOfUpdates = (int) env.config.pointFreezeMillis / 1000;
        try {
            for (int i = numOfUpdates; i > 0; i--) {
                int currentCountdownsSec = i * 1000;
                env.ui.setFreeze(this.id, currentCountdownsSec);
                playerThread.sleep(1000);
            }
        } catch (InterruptedException e) {
        }
        blockInput = false;
        env.ui.setFreeze(this.id, 0);
    }


    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        int numOfUpdates = (int) env.config.penaltyFreezeMillis / 1000;
        try {

            for (int i = numOfUpdates; i > 0; i--) {
                int currentCountdownsSec = i * 1000;
                env.ui.setFreeze(this.id, currentCountdownsSec);
                playerThread.sleep(1000);

            }
        } catch (InterruptedException ignored) {
        }
        env.ui.setFreeze(this.id, 0);
    }

    public int score() {
        return score;
    }

    public Thread getPlayerThread() {
        return this.playerThread;
    }


    /**
     * translate the current slot where the player has put his tokens into an array of cards
     *
     * @post - return an array of 3 cards corresponding to the tokens the player placed on the table
     */
    public int[] getSet() {
        int[] set = new int[env.config.featureSize];
        Arrays.fill(set, -1);
        for (int i = 0; i < tokensPlaced.size(); i++) {
            if (table.slotToCard[tokensPlaced.get(i)] != null)
                set[i] = table.slotToCard[tokensPlaced.get(i)];
        }
        return set;
    }


    /**
     * check if the player has 3 cards currently chosen
     *
     * @post - returns true iff player holds 3 cards currently
     */
    public boolean isSetFull() {
        int[] set = this.getSet();
        for (int s : set) {
            if (s == -1)
                return false;
        }
        return true;
    }

    public List<Integer> getCardsList() {
        List<Integer> playerCards = new ArrayList<>();
        for (int slot : tokensPlaced) {
            if (table.slotToCard[slot] != null)
                playerCards.add(table.slotToCard[slot]);
        }
        return playerCards;
    }
}

//    public void point() {
//        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
//        env.ui.setScore(id, ++score);
//        int numOfUpdates = (int) env.config.pointFreezeMillis / 1000;
//        for (int i = numOfUpdates; i > 0; i--) {
//            int currentCountdownsSec = i * 1000;
//            env.ui.setFreeze(this.id, currentCountdownsSec);
//            try {
//                playerThread.sleep(1000);
//            } catch (InterruptedException e) {
//            }
//        }
//        blockInput = false;
//        env.ui.setFreeze(this.id, 0);
//    }


//    public void penalty() {
//        int numOfUpdates = (int) env.config.penaltyFreezeMillis / 1000;
//        for (int i = numOfUpdates; i > 0; i--) {
//            int currentCountdownsSec = i * 1000;
//            env.ui.setFreeze(this.id, currentCountdownsSec);
//            try {
//                playerThread.sleep(1000);
//
//            } catch (InterruptedException ignored) {
//            }
//        }
//        env.ui.setFreeze(this.id, 0);
//    }









