package bots;

import robocode.*;

import java.awt.*;
import java.awt.geom.Point2D;
import java.util.*;

import static robocode.util.Utils.*;

public class KevinOfNine extends AdvancedRobot {
    //rng
    private Random rng = new Random();

    //control vars
    private int flip = 1;
    private int phase = 0;
    private ScannedRobotEvent target;
    private HashMap<String, ScannedRobotEvent> scanned = new HashMap<>();
    private boolean nearWalls = false;
    private int pendingTargetMode = -1;

    //bullet tuning vars
    private HashMap<Integer, Integer> bulletMap = new HashMap<>(); //TODO: change to enum?

    //last ... vars, for calculating x/y velocities
    private ArrayList<double[]> tracking = new ArrayList<>(); //TODO: change to enum?

    //stats vars
    //nomenclature: hit/miss are our bullts, shot is us getting shot
    private double hitNum = 0;
    private double missNum = 0;
    private double consecutiveMissNum = 0;
    private double lastReset = 0;
    private static HashMap<String, int[][]> targetModeHitsMap = new HashMap<>();
    private double lastDodge = 0;
    private int dodges = 0;

    public void run() {
        //set colours
        setBodyColor(new Color(155, 0, 70));
        setGunColor(new Color(0, 255, 0));
        setRadarColor(new Color(255, 200, 125));
        setBulletColor(new Color(255, 140, 0));
        setScanColor(Color.gray);

        while (true) {
            //movement, avoid edges
            nearWalls = false;
            if (getX() < 200 || getX() > getBattleFieldWidth() - 200 || getY() < 200 || getY() > getBattleFieldHeight() - 200) {
                if (Math.abs(getTurnRemaining()) < 1) {
                    setTurnRight(normalRelativeAngleDegrees(
                            absoluteBearing(
                                    getX(),
                                    getY(),
                                    getBattleFieldWidth()/2,
                                    getBattleFieldHeight()/2
                            ) - getHeading()) / 2);
                    setAhead(150);
                }
                nearWalls = true;
            }

            //move
            if (Math.abs(getDistanceRemaining()) < 16) {
                if (getTime() % 100 > 80) { //TODO: tune? this isn't 50/50 because that seems to help, but if we start facing better predictors we may want to be more random?
                    setAhead(rng.nextInt(150) + 150);
                } else {
                    setBack(rng.nextInt(150) + 150);
                }
            }

            //strafe target if we have one and aren't near a wall
            //TODO: tune? but be careful, this is apparently very important
            if (target != null && !nearWalls) {
                double angle;
                if (target.getDistance() < 100) {
                    angle = 135;
                } else if (target.getDistance() < 200) {
                    angle = 110;
                } else if (target.getDistance() < 300) {
                    angle = 70;
                } else if (target.getDistance() < 400) {
                    angle = 60;
                } else {
                    angle = 45;
                }
                if (getVelocity() < 0) { // going backwards, mod angle
                    angle = 180 - angle;
                }
                setTurnRight(normalRelativeAngleDegrees(target.getBearing() + angle));
            }

            //periodically reset phase, assuming there are > 1 enemies, and we're not on a roll
            if (scanned.size() > 1 && ((consecutiveMissNum > 2 && getTime() - lastReset > 50) || getTime() - lastReset > 100)) {
                lastReset = getTime();
                phase = 0;
            }

            //phase control
            switch (phase) {
                case 0: //start target scan
                    //reset vars
                    hitNum = 0;
                    missNum = 0;
                    tracking.clear();
                    scanned.clear();

                    //scan for targets and advance phase
                    setTurnRadarRight(360);
                    phase = 1;
                    break;

                case 1: //wait for target scan to complete
                    if (getRadarTurnRemaining() == 0) {
                        phase = 2;
                    }
                    break;

                case 2: //targeting
                    target = null;
                    for (Map.Entry<String, ScannedRobotEvent> e : scanned.entrySet()) {
                        if (target == null || e.getValue().getDistance() < target.getDistance()) {
                            target = e.getValue();
                        }
                    }

                    //do we have a target? if yes continue, else reset phase
                    if (target != null) {
                        setTurnRadarRight(720); //turn radar full circle twice, advanced to phase 3
                        phase = 3;
                    } else {
                        phase = 0;
                    }
                    break;

                case 3: //attacking phase
                    //this is to catch a case where our target was destroyed by someone other than us
                    //if the radar spinning completes, we should reset to phase 0
                    if (Math.abs(getRadarTurnRemaining()) == 0) {
                        phase = 0;
                    }
                    //defer to scanner logic...
                    break;
            }
            execute();
        }
    }

    public void onScannedRobot(ScannedRobotEvent e) {
        if (phase < 2){ //scanning
            scanned.put(e.getName(), e);
        } else if (target != null && e.getName() == target.getName()) { //re-scanned target
            //dodge?
            //FIXME: BE CAREFUL WITH THIS...
            //it seems to be very impactful one way or the other, good against predictable bots, bad against unpredictable ones
            double energyDelta = target.getEnergy() - e.getEnergy();
            if (energyDelta > 0 && energyDelta <= 3) { //target appears to have fired
                if (!nearWalls //don't dodge when near walls
                        && scanned.size() == 1 //only if there's one opponent
                        && dodges > 0 //only dodge if we're getting shot, and dodging isn't getting us shot - see onHitBy... f'n
                        && getTime() - lastDodge > 5 //don't over-dodge
                        && Math.abs(getVelocity()) > 7 //don't stutter
                        && e.getDistance() > 125 && e.getDistance() < 300) { //don't dodge if enemy is too far/close
                    double dr = getDistanceRemaining() * -1;
                    if (dr < 0) {
                        dr = Math.min(dr, -100);
                    } else {
                        dr = Math.max(dr, 100);
                    }
                    setAhead(dr);
                    lastDodge = getTime();
                    dodges = 0;
                }
            }

            //radar && targeting
            target = e; //since we re-scanned our target, update to latest info
            flip *= -1;
            setTurnRadarRight(720 * flip);

            double power; //TODO: tune
            if (target.getDistance() < 150 && getEnergy() > 9) {
                power = 3;
            } else if (target.getDistance() < 400 && getEnergy() > 6) {
                power = 2;
            } else {
                power = 1;
            }

            //pick targeting mode
            int targetMode;
            if (pendingTargetMode == -1) { //pick a new target mode if we aren't waiting to fire for a previous choice
                targetMode = rng.nextInt(4); //start w/ a random mode
                //potentially change targeting mode based on stats
                if (targetModeHitsMap.containsKey(target.getName())) { //if we have stats for this target...
                    //total all the stats
                    int[][] stats = targetModeHitsMap.get(target.getName());
                    long[] accuracy = new long[4];
                    int sum = 0;
                    for (int i = 0; i < stats.length; i++) {
                        //the +# below is because we don't want to get locked into a mode too early
                        //TODO: tune? AdvancedSpinbot seemed to trigger this the most often
                        int shots = stats[i][0] + 10;
                        int hits = stats[i][1] + 5;
                        //calculate accuracy as a percentage, plus the discrete number of hits
                        //adding the # of discrete hits minimizes recycling lower-success methods over time, TODO: tune?
                        accuracy[i] = Math.round((double) hits / shots * 100) + hits;
                        //accuracy[i] *= accuracy[i];
                        //add to sum for below
                        sum += accuracy[i];
                    }

                    //pick a random number between 0 and sum, essentially assigning each mode a range based on its stats
                    //eg. if our modes have 10,20,30,40 accuracies respectively, r will be out of 100
                    //if r < 10, mode will be 1, if r is between 10 and 29 it will be 2, etc...
                    int r = rng.nextInt(sum);
                    for (int i = 0; i < accuracy.length; i++) {
                        if (r < accuracy[i]) {
                            targetMode = i;
                            break;
                        } else {
                            r -= accuracy[i]; //be careful of this if debugging, we modify r as we loop
                        }
                    }
                }

                //save chosen mode to use on future scan calls while turret is adjusting
                pendingTargetMode = targetMode;
            } else { //we have a previous choice, tune until we're ready to fire
                targetMode = pendingTargetMode;
            }

            //predictive aiming vars
            //adapted from: http://mark.random-article.com/weber/java/robocode/lesson4.html
            double absBearing = normalAbsoluteAngle(getHeadingRadians() + target.getBearingRadians());
            double bearing;
            double speed =  20 - power*3;
            double time = target.getDistance() / speed;
            double txf, tyf; //target x/y future (coordinates we shoot at)
            int targetModeUsed;

            //target x (current)
            double tx = getX() + Math.sin(absBearing)*target.getDistance();
            double ty = getY() + Math.cos(absBearing)*target.getDistance();

            //shoot directly (last resort targeting method, #3)
            txf = tx;
            tyf = ty;
            targetModeUsed = 3;

            //time dilation based on whether target is approaching/fleeing
            if (tracking.size() > 0) {
                double ld = target.getDistance();
                double[] telemetry;
                for (int i = tracking.size() - 1; i >= 0; i--) {
                    telemetry = tracking.get(i);
                    if (getTime() - telemetry[3] > 10) break; //TODO: tune
                    ld = telemetry[2];
                }
                time *= target.getDistance() / ld;
            }

            //target x/y future by heading
            if (targetMode < 3) { //standard targeting method #0 - also used when there is no tracking data for #1 and #2
                double txfh = tx + Math.sin(target.getHeadingRadians()) * target.getVelocity() * time;
                double tyfh = ty + Math.cos(target.getHeadingRadians()) * target.getVelocity() * time;
                //set targeting
                txf = txfh;
                tyf = tyfh;
                targetModeUsed = 0;
            }

            //target x/y future by x/y velocities and avg prev position
            double trackingLimit = time; //track backwards the amount we're about to predict forwards, TODO: tune?
            if (tracking.size() > 0 && getTime() - tracking.get(tracking.size() - 1)[3] <= trackingLimit) { //do we have non-stale tracking data?
                //last vars
                double lx = 0;
                double ly = 0;
                double lt = 0;
                //sum vars
                double sx = 0;
                double sy = 0;
                double sd = 0;
                double[] telemetry;
                for (int i = tracking.size() - 1; i >= 0; i--) {
                    telemetry = tracking.get(i);
                    if (getTime() - telemetry[3] > trackingLimit) break;
                    lx = telemetry[0];
                    ly = telemetry[1];
                    lt = telemetry[3];
                    sx += telemetry[0];
                    sy += telemetry[1];
                    sd++;
                    break;
                }

                if (targetMode == 1) { //target mode #1, x/y velocities
                    double vx = (tx - lx) / (getTime() - lt);
                    double vy = (ty - ly) / (getTime() - lt);
                    txf = tx + vx*time;
                    tyf = ty + vy*time;
                    targetModeUsed = 1;
                } else if (targetMode == 2) { //target mode #2, avg prev position
                    txf = sx / sd;
                    tyf = sy / sd;
                    targetModeUsed = 2;
                }
            }

            //store target telemetry for future targetting
            tracking.add(new double[]{tx, ty, target.getDistance(), (double)getTime()});

            //clamp to walls
            if (txf < 0) txf = 0;
            if (txf > getBattleFieldWidth()) txf = getBattleFieldWidth();
            if (tyf < 0) tyf = 0;
            if (tyf > getBattleFieldHeight()) tyf = getBattleFieldHeight();

            //aim
            bearing = normalRelativeAngleDegrees(absoluteBearing(getX(), getY(), txf, tyf) - getGunHeading());
            setTurnGunRight(bearing);

            //fire!
            if (Math.abs(getGunTurnRemaining()) < 6 && getGunHeat() == 0) { //TODO: tune?
                Bullet bullet = fireBullet(power);
                if (bullet != null) { //bullet tracking for stats
                    if (!targetModeHitsMap.containsKey(target.getName())) {
                        targetModeHitsMap.put(target.getName(), new int[4][2]);
                    }
                    targetModeHitsMap.get(target.getName())[targetModeUsed][0]++;
                    bulletMap.put(bullet.hashCode(), targetModeUsed);
                }
                pendingTargetMode = -1; //we've fired, indicate we're ready to pick a new target mode
            }
        }
    }

    // computes the absolute bearing between two points
    double absoluteBearing(double x1, double y1, double x2, double y2) {
        double xo = x2-x1;
        double yo = y2-y1;
        double hyp = Point2D.distance(x1, y1, x2, y2);
        double arcSin = Math.toDegrees(Math.asin(xo / hyp));
        double bearing = 0;

        if (xo > 0 && yo > 0) { // both pos: lower-Left
            bearing = arcSin;
        } else if (xo < 0 && yo > 0) { // x neg, y pos: lower-right
            bearing = 360 + arcSin; // arcsin is negative here, actuall 360 - ang
        } else if (xo > 0 && yo < 0) { // x pos, y neg: upper-left
            bearing = 180 - arcSin;
        } else if (xo < 0 && yo < 0) { // both neg: upper-right
            bearing = 180 - arcSin; // arcsin is negative here, actually 180 + ang
        }

        return bearing;
    }

    //reset, retarget, respond to closest
    public void onHitRobot(HitRobotEvent e) {
        if (hitNum + missNum > 3) { //don't get reset locked
            setTurnRight(normalRelativeAngleDegrees(target.getBearing() + 90));
            if (Math.abs(normalRelativeAngleDegrees(e.getBearing())) < 90) {
                setBack(200);
            } else {
                setAhead(200);
            }
            if (scanned.size() > 1) {
                phase = 0;
            }
        }
    }

    public void onHitByBullet(HitByBulletEvent e) {
        //dodge control
        //see block in scanner handling
        if (getTime() - lastDodge > 16) { //if we're getting shot and it isn't after a dodge, enable dodging
            if (dodges < 1) {
                dodges++;
            }
        } else { //if we're shot after dodging, decrement, this likely lands us at -1 so there is some cooldown (which we want)
            dodges--;
        }

        //reset phase and re-target if someone other than our target shot us
        if (scanned.size() > 1 && phase > 2 && e.getName() != target.getName() && hitNum + missNum > 3) { //don't get reset locked
            phase = 0;
        }
    }

    public void onBulletHit(BulletHitEvent e) {
        if (e.getName() == target.getName()) {
            if (e.getEnergy() <= 0) { //reset phase when killing target
                phase = 0;
            }
            consecutiveMissNum = 0;
            hitNum++;

            //track stats
            if (bulletMap.containsKey(e.getBullet().hashCode())) {
                int mode = bulletMap.get(e.getBullet().hashCode());
                targetModeHitsMap.get(target.getName())[mode][1]++; //record hit, this is by reference so no need to put afterwards
            }
        }
    }

    public void onBulletMissed(BulletMissedEvent e) {
        missNum++;
        consecutiveMissNum++;
        //TODO: anything re: targetMode? or just base off hit %s
    }

    public void onWin(WinEvent e) {
        stop();
        while (true) {
            setTurnRadarLeft(1337);
            setTurnGunRight(1337);
            turnLeft(8);
            ahead(8);
            turnRight(8);
            back(8);
        }
    }

    public void onRoundEnded(RoundEndedEvent e) {
        for (Map.Entry<String, int[][]> hitStats : targetModeHitsMap.entrySet()) {
            System.out.print(hitStats.getKey() + ": ");
            int[][] values = hitStats.getValue();
            for (int i = 0; i < values.length; i++) {
                int shots = values[i][0];
                int hits = values[i][1];
                int accuracy = (int)Math.round((double)hits/shots*100);
                System.out.print(" " + hits + "/" + shots + "=" + accuracy + "%");
            }
            System.out.println();
        }
    }
}
