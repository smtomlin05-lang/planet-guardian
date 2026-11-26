package com.smtomlin.planetguardian;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.ArrayList;
import java.util.Random;

/**
 * Planet Guardian - an original side-scrolling shooter in the spirit of the
 * early-80s arcade classics. Everything is drawn in "virtual units": the screen
 * is always 1000 units tall and as wide as the aspect ratio allows.
 */
public class GameView extends SurfaceView implements SurfaceHolder.Callback, Runnable {

    // ---- world constants ----
    static final float VH = 1000f;      // virtual screen height
    static final float PT = 125f;       // top of the play area (scanner sits above)
    static final float GROUND = 972f;   // humanoids walk here
    static final float W = 9600f;       // world width (wraps round)
    static final int HUMANS = 10;
    static final float SZ = 1.4f;       // ship/enemy drawing scale

    static final int TITLE = 0, PLAYING = 1, WAVE_END = 2, GAME_OVER = 3;
    static final int LANDER = 0, MUTANT = 1, BOMBER = 2, POD = 3, SWARMER = 4, BAITER = 5;
    static final int H_WALK = 0, H_CARRIED = 1, H_FALLING = 2, H_RESCUED = 3;
    static final int[] POINTS = {150, 150, 250, 1000, 150, 200};
    static final int[] COL = {0xFF44FF44, 0xFFFF44FF, 0xFF4499FF, 0xFFCC55FF, 0xFFFF4444, 0xFF99FF66};

    // ---- entity types ----
    static class Enemy {
        int type, state;
        float x, y, vx, vy, t, shoot, baseY;
        Human carry, target;
        boolean dead;
    }

    static class Human {
        float x, y, vx, vy, fallFrom;
        int state;
        Enemy claim;
        boolean dead;
    }

    static class Bullet {
        float x, y, vx, vy, life;
        boolean mine;
    }

    static class Laser {
        float x0, y, head, tail, life;
        int dir;
        boolean dead;
    }

    static class Particle {
        float x, y, vx, vy, life, max, size;
        int color;
    }

    static class Popup {
        float x, y, life;
        String text;
    }

    // ---- view / thread ----
    float VW = 1800f, scale = 1f;
    private Thread thread;
    private volatile boolean running, surfaceReady;
    private boolean paused;
    private final Random rnd = new Random();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final Sound sound;
    private final SharedPreferences prefs;

    // ---- input (written on UI thread, read on game thread) ----
    private volatile int stickId = -1;
    private volatile float stickAx, stickAy, stickX, stickY;
    private volatile boolean fireHeld, bombHeld, hyperHeld, revHeld, tapped;
    private boolean prevBomb, prevHyper, prevRev;
    private int lastStickDir;
    private float fireX, fireY, fireR, bombX, bombY, hypX, hypY, revX, revY, smallR;

    // ---- game state ----
    int state = TITLE;
    float stateTimer, bannerTimer, flash, waveTime, baiterTimer, spawnTimer, deathTimer;
    int score, hiScore, lives, bombs, wave, nextBonus, landersToSpawn, waveBonus;
    boolean planetAlive = true;

    float px, py, pvx, invuln, fireCool, hyperCool, camOff, camC;
    int facing = 1;
    boolean alive, thrusting;
    Human carried;

    final ArrayList<Enemy> enemies = new ArrayList<>();
    final ArrayList<Enemy> newEnemies = new ArrayList<>();
    final ArrayList<Human> humans = new ArrayList<>();
    final ArrayList<Bullet> bullets = new ArrayList<>();
    final ArrayList<Laser> lasers = new ArrayList<>();
    final ArrayList<Particle> particles = new ArrayList<>();
    final ArrayList<Popup> popups = new ArrayList<>();

    final float[] terrain = new float[(int) (W / 30f)];
    final float[] starX = new float[140], starY = new float[140];
    final int[] starC = new int[140];

    public GameView(Context ctx) {
        super(ctx);
        getHolder().addCallback(this);
        setFocusable(true);
        prefs = ctx.getSharedPreferences("planet_guardian", Context.MODE_PRIVATE);
        hiScore = prefs.getInt("hi", 0);
        sound = new Sound();
        makeTerrain();
        for (int i = 0; i < starX.length; i++) {
            starX[i] = rnd.nextFloat() * 3000f;
            starY[i] = PT + rnd.nextFloat() * 700f;
            int[] cols = {0xFF888888, 0xFFCCCCFF, 0xFFFFCC88, 0xFF666699};
            starC[i] = cols[rnd.nextInt(cols.length)];
        }
        paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        px = W / 2;
        py = 500;
        camC = px;
    }

    // =====================================================================
    // lifecycle
    // =====================================================================
    public void resume() {
        if (running) return;
        running = true;
        if (state == PLAYING || state == WAVE_END) paused = true;
        thread = new Thread(this, "game");
        thread.start();
    }

    public void pause() {
        running = false;
        if (thread != null) {
            try { thread.join(500); } catch (InterruptedException ignored) { }
            thread = null;
        }
        if (score > hiScore) saveHi();
    }

    @Override public void surfaceCreated(SurfaceHolder h) { surfaceReady = true; }

    @Override public void surfaceChanged(SurfaceHolder h, int fmt, int w, int ht) {
        scale = ht / VH;
        VW = w / scale;
        fireR = 92; fireX = VW - 135; fireY = VH - 140;
        smallR = 62;
        bombX = VW - 330; bombY = VH - 95;
        hypX = VW - 135; hypY = VH - 345;
        revX = VW - 335; revY = VH - 290;
    }

    @Override public void surfaceDestroyed(SurfaceHolder h) { surfaceReady = false; }

    @Override
    public void run() {
        long last = System.nanoTime();
        while (running) {
            long now = System.nanoTime();
            float dt = (now - last) / 1e9f;
            last = now;
            if (dt > 0.05f) dt = 0.05f;
            try {
                update(dt);
            } catch (RuntimeException e) {
                // never let one bad frame kill the game
            }
            if (surfaceReady) {
                Canvas c = null;
                SurfaceHolder h = getHolder();
                try {
                    c = Build.VERSION.SDK_INT >= 26 ? h.lockHardwareCanvas() : h.lockCanvas();
                    if (c != null) draw2(c);
                } catch (RuntimeException ignored) {
                } finally {
                    if (c != null) {
                        try { h.unlockCanvasAndPost(c); } catch (RuntimeException ignored) { }
                    }
                }
            } else {
                try { Thread.sleep(16); } catch (InterruptedException ignored) { }
            }
        }
    }

    // =====================================================================
    // input
    // =====================================================================
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        int idx = e.getActionIndex();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            tapped = true;
            float x = e.getX(idx) / scale, y = e.getY(idx) / scale;
            if (stickId == -1 && x < VW * 0.42f) {
                stickId = e.getPointerId(idx);
                stickAx = stickX = x;
                stickAy = stickY = y;
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP
                || action == MotionEvent.ACTION_CANCEL) {
            if (action == MotionEvent.ACTION_CANCEL || e.getPointerId(idx) == stickId) stickId = -1;
        }
        boolean f = false, b = false, hy = false, rv = false;
        for (int i = 0; i < e.getPointerCount(); i++) {
            if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) && i == idx) continue;
            if (action == MotionEvent.ACTION_CANCEL) continue;
            int id = e.getPointerId(i);
            float x = e.getX(i) / scale, y = e.getY(i) / scale;
            if (id == stickId) {
                stickX = x;
                stickY = y;
                continue;
            }
            if (near(x, y, fireX, fireY, fireR * 1.35f)) f = true;
            else if (near(x, y, bombX, bombY, smallR * 1.3f)) b = true;
            else if (near(x, y, hypX, hypY, smallR * 1.3f)) hy = true;
            else if (near(x, y, revX, revY, smallR * 1.4f)) rv = true;
            else if (x >= VW * 0.42f) f = true;   // anywhere else on the right fires
        }
        fireHeld = f;
        bombHeld = b;
        hyperHeld = hy;
        revHeld = rv;
        return true;
    }

    private static boolean near(float x, float y, float cx, float cy, float r) {
        float dx = x - cx, dy = y - cy;
        return dx * dx + dy * dy < r * r;
    }

    // =====================================================================
    // helpers
    // =====================================================================
    static float wrap(float x) {
        x %= W;
        if (x < 0) x += W;
        return x;
    }

    /** shortest signed distance a - b round the wrapped world */
    static float dxw(float a, float b) {
        float d = (a - b) % W;
        if (d > W / 2) d -= W;
        if (d < -W / 2) d += W;
        return d;
    }

    float sx(float x) { return dxw(x, camC) + VW / 2; }

    boolean onScreen(float x) {
        float s = sx(x);
        return s > -40 && s < VW + 40;
    }

    static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

    float rr(float a, float b) { return a + rnd.nextFloat() * (b - a); }

    float mul() { return Math.min(1f + 0.08f * (wave - 1), 1.9f); }

    void makeTerrain() {
        int n = terrain.length;
        float y = 900, slope = 0;
        for (int i = 0; i < n; i++) {
            slope += rr(-9, 9);
            slope = clamp(slope, -22, 22);
            y += slope;
            if (y < 790) { y = 790; slope = Math.abs(slope); }
            if (y > 962) { y = 962; slope = -Math.abs(slope); }
            terrain[i] = y;
        }
        // blend the end into the start so the wrap is seamless
        int blend = 25;
        for (int i = 0; i < blend; i++) {
            float k = (i + 1) / (float) (blend + 1);
            int j = n - blend + i;
            terrain[j] = terrain[j] * (1 - k) + terrain[0] * k;
        }
    }

    void addScore(int n) {
        score += n;
        if (score >= nextBonus) {
            nextBonus += 10000;
            lives++;
            bombs++;
            popup(px, py - 60, "BONUS SHIP + BOMB");
            sound.play(sound.bonus);
        }
    }

    void popup(float x, float y, String s) {
        Popup p = new Popup();
        p.x = x; p.y = y; p.text = s; p.life = 1.2f;
        popups.add(p);
    }

    void explode(float x, float y, int color, int count, float speed) {
        for (int i = 0; i < count && particles.size() < 900; i++) {
            Particle p = new Particle();
            double a = rnd.nextDouble() * Math.PI * 2;
            float s = rr(0.2f, 1f) * speed;
            p.x = x; p.y = y;
            p.vx = (float) Math.cos(a) * s;
            p.vy = (float) Math.sin(a) * s;
            p.max = p.life = rr(0.4f, 1.1f);
            p.size = rr(3, 7);
            p.color = rnd.nextInt(3) == 0 ? 0xFFFFFFFF : color;
            particles.add(p);
        }
    }

    void saveHi() {
        if (score > hiScore) hiScore = score;
        prefs.edit().putInt("hi", hiScore).apply();
    }

    // =====================================================================
    // game flow
    // =====================================================================
    void newGame() {
        score = 0;
        lives = 3;
        bombs = 3;
        nextBonus = 10000;
        particles.clear();
        popups.clear();
        px = W / 2;
        camC = px;
        startWave(1);
    }

    void startWave(int n) {
        wave = n;
        if (n == 1 || n % 5 == 0) {
            planetAlive = true;
            humans.clear();
            for (int i = 0; i < HUMANS; i++) {
                Human h = new Human();
                h.x = (i + rr(0.1f, 0.9f)) * W / HUMANS;
                h.y = GROUND;
                h.vx = rr(10, 25) * (rnd.nextBoolean() ? 1 : -1);
                humans.add(h);
            }
        } else {
            for (Human h : humans) {
                h.claim = null;
                if (h.state != H_WALK) { h.state = H_WALK; h.y = GROUND; }
            }
        }
        enemies.clear();
        newEnemies.clear();
        bullets.clear();
        lasers.clear();
        carried = null;
        landersToSpawn = Math.min(10 + n * 2, 24);
        spawnTimer = 1.0f;
        for (int i = 0; i < (n >= 2 ? Math.min(1 + n / 3, 4) : 0); i++) spawn(BOMBER, randomFarX());
        for (int i = 0; i < (n >= 3 ? Math.min(n / 3, 3) : 0); i++) spawn(POD, randomFarX());
        waveTime = 0;
        baiterTimer = 45;
        alive = true;
        py = 500;
        pvx = 0;
        invuln = 2f;
        bannerTimer = 2.2f;
        state = PLAYING;
    }

    float randomFarX() {
        return wrap(px + VW + rnd.nextFloat() * (W - 2 * VW));
    }

    Enemy spawn(int type, float x) {
        Enemy e = new Enemy();
        e.type = type;
        e.x = wrap(x);
        e.t = rr(0, 10);
        float m = mul();
        switch (type) {
            case LANDER:
                e.y = PT + rr(30, 160);
                e.vx = rr(50, 100) * m * (rnd.nextBoolean() ? 1 : -1);
                e.shoot = rr(2, 5);
                break;
            case MUTANT:
                e.y = PT + rr(30, 300);
                e.shoot = rr(1.5f, 3);
                break;
            case BOMBER:
                e.baseY = rr(300, 700);
                e.y = e.baseY;
                e.vx = rr(60, 110) * (rnd.nextBoolean() ? 1 : -1);
                e.shoot = rr(0.5f, 1.5f);
                break;
            case POD:
                e.y = rr(250, 750);
                e.vx = rr(30, 60) * (rnd.nextBoolean() ? 1 : -1);
                e.vy = rr(20, 50) * (rnd.nextBoolean() ? 1 : -1);
                break;
            case SWARMER:
                e.vx = rr(-200, 200);
                e.shoot = rr(2, 5);
                break;
            case BAITER:
                e.y = PT + 60;
                e.shoot = rr(1, 2);
                break;
        }
        newEnemies.add(e);
        return e;
    }

    void killEnemy(Enemy e, boolean byBomb) {
        if (e.dead) return;
        e.dead = true;
        addScore(POINTS[e.type]);
        explode(e.x, e.y, COL[e.type], e.type == POD ? 40 : 22, 380);
        sound.play(sound.boom);
        if (e.carry != null) {
            Human h = e.carry;
            h.state = H_FALLING;
            h.vy = 0;
            h.fallFrom = h.y;
            h.claim = null;
            e.carry = null;
        }
        if (e.target != null) {
            if (e.target.claim == e) e.target.claim = null;
            e.target = null;
        }
        if (e.type == POD && !byBomb) {
            int n = 4 + rnd.nextInt(3);
            for (int i = 0; i < n; i++) {
                Enemy s = spawn(SWARMER, e.x + rr(-20, 20));
                s.y = e.y + rr(-20, 20);
            }
        }
    }

    void killPlayer() {
        if (!alive) return;
        alive = false;
        lives--;
        deathTimer = 2.6f;
        explode(px, py, 0xFFFFFFFF, 60, 520);
        explode(px, py, 0xFFFF3399, 40, 300);
        sound.play(sound.die);
        bullets.clear();
        lasers.clear();
        if (carried != null) {
            carried.state = H_FALLING;
            carried.vy = 0;
            carried.fallFrom = carried.y;
            carried = null;
        }
    }

    void destroyPlanet() {
        planetAlive = false;
        flash = 1.2f;
        sound.play(sound.die);
        for (Enemy e : enemies) {
            if (e.type == LANDER && !e.dead) {
                e.type = MUTANT;
                e.state = 0;
                e.carry = null;
                e.target = null;
            }
        }
        popup(px, 400, "PLANET LOST");
    }

    // =====================================================================
    // update
    // =====================================================================
    void update(float dt) {
        if (paused) {
            if (tapped) { paused = false; tapped = false; }
            return;
        }
        if (flash > 0) flash -= dt;
        switch (state) {
            case TITLE:
                camC = wrap(camC + 60 * dt);
                if (tapped) { tapped = false; newGame(); }
                break;
            case PLAYING:
                updatePlay(dt);
                break;
            case WAVE_END:
                stateTimer -= dt;
                if (stateTimer <= 0) startWave(wave + 1);
                break;
            case GAME_OVER:
                stateTimer -= dt;
                updateEnemies(dt);
                if (stateTimer <= 0 && tapped) { state = TITLE; }
                break;
        }
        updateParticles(dt);
        tapped = false;
    }

    void updatePlay(float dt) {
        waveTime += dt;
        if (bannerTimer > 0) bannerTimer -= dt;

        // ---- player ----
        float oldCam = camC;
        if (alive) {
            boolean stick = stickId >= 0;
            float sdx = stick ? stickX - stickAx : 0, sdy = stick ? stickY - stickAy : 0;
            py += clamp(sdy / 70f, -1, 1) * 600 * dt;
            py = clamp(py, PT + 30, GROUND - 24);
            thrusting = false;
            if (revHeld && !prevRev) facing = -facing;
            int stickDir = Math.abs(sdx) > 28 ? (sdx > 0 ? 1 : -1) : 0;
            if (stickDir != 0 && stickDir != lastStickDir) facing = stickDir;
            lastStickDir = stickDir;
            if (stickDir != 0) {
                float amt = clamp((Math.abs(sdx) - 28) / 70f, 0, 1);
                pvx += facing * amt * 1800 * dt;
                thrusting = amt > 0.05f;
            }
            pvx *= (float) Math.pow(thrusting ? 0.7 : 0.4, dt);
            pvx = clamp(pvx, -1100, 1100);
            px = wrap(px + pvx * dt);
            if (invuln > 0) invuln -= dt;

            // fire
            fireCool -= dt;
            if (fireHeld && fireCool <= 0 && lasers.size() < 5) {
                Laser l = new Laser();
                l.x0 = wrap(px + facing * 52);
                l.y = py + 4;
                l.dir = facing;
                l.life = 0.55f;
                lasers.add(l);
                fireCool = 0.15f;
                sound.play(sound.laser);
            }
            // smart bomb
            if (bombHeld && !prevBomb && bombs > 0) {
                bombs--;
                flash = 0.25f;
                sound.play(sound.bomb);
                for (Enemy e : enemies) if (!e.dead && onScreen(e.x)) killEnemy(e, true);
                for (int i = bullets.size() - 1; i >= 0; i--) if (onScreen(bullets.get(i).x)) bullets.remove(i);
            }
            // hyperspace
            hyperCool -= dt;
            if (hyperHeld && !prevHyper && hyperCool <= 0) {
                hyperCool = 1.0f;
                sound.play(sound.warp);
                explode(px, py, 0xFF66CCFF, 20, 300);
                px = rnd.nextFloat() * W;
                py = rr(PT + 60, GROUND - 120);
                pvx = 0;
                facing = rnd.nextBoolean() ? 1 : -1;
                camOff = facing * VW * 0.25f;
                camC = wrap(px + camOff);
                oldCam = camC;
                if (carried != null) { carried.x = px; carried.y = py + 40; }
                if (rnd.nextInt(8) == 0 && invuln <= 0) killPlayer();
            }
        } else {
            thrusting = false;
            deathTimer -= dt;
            if (deathTimer <= 0) {
                if (lives > 0) {
                    alive = true;
                    invuln = 2.5f;
                    pvx = 0;
                    py = 500;
                } else {
                    state = GAME_OVER;
                    stateTimer = 1.5f;
                    saveHi();
                }
            }
        }
        prevBomb = bombHeld;
        prevHyper = hyperHeld;
        prevRev = revHeld;

        // ---- camera ----
        float target = facing * VW * 0.25f;
        camOff += (target - camOff) * Math.min(1f, dt * 2.2f);
        camC = wrap(px + camOff);
        float camDelta = dxw(camC, oldCam);
        for (int i = 0; i < starX.length; i++) {
            starX[i] -= camDelta * 0.25f;
            if (starX[i] < 0) starX[i] += 3000;
            if (starX[i] >= 3000) starX[i] -= 3000;
        }

        // ---- spawning ----
        spawnTimer -= dt;
        boolean anyLander = false;
        for (Enemy e : enemies) if (!e.dead && (e.type == LANDER || e.type == MUTANT)) { anyLander = true; break; }
        if (landersToSpawn > 0 && (spawnTimer <= 0 || !anyLander)) {
            int n = Math.min(5, landersToSpawn);
            landersToSpawn -= n;
            for (int i = 0; i < n; i++) {
                Enemy e = spawn(planetAlive ? LANDER : MUTANT, randomFarX());
                explode(e.x, e.y, COL[e.type], 8, 120);
            }
            spawnTimer = 14f;
            anyLander = true;
        }
        baiterTimer -= dt;
        if (baiterTimer <= 0) {
            baiterTimer = 15;
            spawn(BAITER, px + (rnd.nextBoolean() ? 1 : -1) * VW * 0.7f);
        }

        updateEnemies(dt);
        updateHumans(dt);
        updateLasers(dt);
        updateBullets(dt);

        for (Popup p : popups) { p.life -= dt; p.y -= 40 * dt; }
        for (int i = popups.size() - 1; i >= 0; i--) if (popups.get(i).life <= 0) popups.remove(i);

        // ---- wave complete? ----
        if (landersToSpawn == 0 && !anyLander && alive) {
            int alivePeople = 0;
            for (Human h : humans) if (!h.dead) alivePeople++;
            waveBonus = planetAlive ? alivePeople * 100 * Math.min(wave, 5) : 0;
            addScore(waveBonus);
            state = WAVE_END;
            stateTimer = 3.5f;
            sound.play(sound.bonus);
        }
    }

    void updateEnemies(float dt) {
        float m = mul();
        for (Enemy e : enemies) {
            if (e.dead) continue;
            e.t += dt;
            float dPlayer = dxw(px, e.x);
            switch (e.type) {
                case LANDER: updateLander(e, dt, m); break;
                case MUTANT: {
                    float sp = Math.abs(dPlayer) < VW ? 270 * m : 160 * m;
                    e.vx += Math.signum(dPlayer) * 900 * dt;
                    e.vx = clamp(e.vx, -sp, sp);
                    e.x = wrap(e.x + e.vx * dt);
                    float dy = py - e.y;
                    e.y += Math.signum(dy) * Math.min(Math.abs(dy), 170 * m * dt) + rr(-1, 1) * 420 * dt;
                    break;
                }
                case BOMBER:
                    e.x = wrap(e.x + e.vx * dt);
                    e.y = e.baseY + (float) Math.sin(e.t * 0.9f) * 90;
                    e.shoot -= dt;
                    if (e.shoot <= 0) {
                        e.shoot = rr(0.8f, 1.4f);
                        if (Math.abs(dPlayer) < VW * 1.2f) {
                            Bullet b = new Bullet();
                            b.x = e.x; b.y = e.y; b.mine = true; b.life = 5f;
                            bullets.add(b);
                        }
                    }
                    break;
                case POD:
                    e.x = wrap(e.x + e.vx * dt);
                    e.y += e.vy * dt;
                    if (e.y < PT + 30 || e.y > GROUND - 80) e.vy = -e.vy;
                    break;
                case SWARMER: {
                    float sp = 400 * m;
                    e.vx += Math.signum(dPlayer) * 700 * dt;
                    e.vx = clamp(e.vx, -sp, sp);
                    e.x = wrap(e.x + e.vx * dt);
                    e.y += (py - e.y) * dt * 1.2f + (float) Math.sin(e.t * 7) * 160 * dt;
                    break;
                }
                case BAITER: {
                    float sp = Math.max(Math.abs(pvx) + 160, 380);
                    e.vx += Math.signum(dPlayer) * 1100 * dt;
                    e.vx = clamp(e.vx, -sp, sp);
                    e.x = wrap(e.x + e.vx * dt);
                    float ty = py - 90 + (float) Math.sin(e.t * 2) * 60;
                    e.y += (ty - e.y) * dt * 1.5f;
                    break;
                }
            }
            e.y = clamp(e.y, PT + 18, GROUND - 16);

            // shooting
            if (e.type != BOMBER && e.type != POD) {
                e.shoot -= dt;
                if (e.shoot <= 0) {
                    float base;
                    switch (e.type) {
                        case MUTANT: base = 2.2f; break;
                        case SWARMER: base = 4f; break;
                        case BAITER: base = 1.2f; break;
                        default: base = 3.6f;
                    }
                    e.shoot = rr(base * 0.7f, base * 1.3f) / m;
                    if (alive && state == PLAYING && onScreen(e.x) && Math.abs(dPlayer) > 60) {
                        float tx = dPlayer + pvx * 0.35f, ty = py - e.y;
                        float len = (float) Math.sqrt(tx * tx + ty * ty);
                        if (len > 1) {
                            float s = 330 * m;
                            Bullet b = new Bullet();
                            b.x = e.x; b.y = e.y;
                            b.vx = tx / len * s + pvx * 0.3f;
                            b.vy = ty / len * s;
                            b.life = 3f;
                            bullets.add(b);
                        }
                    }
                }
            }

            // collide with player
            if (alive && state == PLAYING && invuln <= 0
                    && Math.abs(dxw(e.x, px)) < 50 && Math.abs(e.y - py) < 34) {
                killEnemy(e, false);
                killPlayer();
            }
        }
        for (int i = enemies.size() - 1; i >= 0; i--) if (enemies.get(i).dead) enemies.remove(i);
        enemies.addAll(newEnemies);
        newEnemies.clear();
    }

    void updateLander(Enemy e, float dt, float m) {
        switch (e.state) {
            case 0: { // seek
                Human tg = e.target;
                if (tg != null && (tg.dead || tg.state != H_WALK || tg.claim != e)) {
                    if (tg.claim == e) tg.claim = null;
                    e.target = tg = null;
                }
                if (tg == null && planetAlive && rnd.nextFloat() < dt * 0.6f) {
                    for (Human h : humans) {
                        if (!h.dead && h.state == H_WALK && h.claim == null && Math.abs(dxw(h.x, e.x)) < 700) {
                            h.claim = e;
                            e.target = tg = h;
                            break;
                        }
                    }
                }
                float ty = 600 + (float) Math.sin(e.t * 1.3f) * 90;
                if (tg != null) {
                    float d = dxw(tg.x, e.x);
                    float sp = 130 * m * dt;
                    e.x = wrap(e.x + clamp(d, -sp, sp));
                    if (Math.abs(d) < 8) e.state = 1;
                    ty = 700;
                } else {
                    e.x = wrap(e.x + e.vx * dt);
                }
                e.y += (ty - e.y) * dt * 0.6f;
                break;
            }
            case 1: { // descend onto the humanoid
                Human tg = e.target;
                if (tg == null || tg.dead || tg.state != H_WALK) { e.state = 0; break; }
                e.x = tg.x;
                e.y += 95 * m * dt;
                if (e.y >= tg.y - 46) {
                    tg.state = H_CARRIED;
                    e.carry = tg;
                    e.state = 2;
                    if (onScreen(e.x)) sound.play(sound.grab);
                }
                break;
            }
            case 2: { // climb away with it
                Human h = e.carry;
                if (h == null) { e.state = 0; break; }
                e.y -= 52 * m * Math.min(1, dt);
                h.x = e.x;
                h.y = e.y + 46;
                if (e.y <= PT + 14) {
                    h.dead = true;
                    h.claim = null;
                    e.carry = null;
                    e.target = null;
                    e.type = MUTANT;
                    e.state = 0;
                    explode(e.x, e.y, COL[MUTANT], 14, 200);
                    if (onScreen(e.x)) sound.play(sound.grab);
                    checkPlanet();
                }
                break;
            }
        }
    }

    void checkPlanet() {
        if (!planetAlive) return;
        for (Human h : humans) if (!h.dead) return;
        destroyPlanet();
    }

    void updateHumans(float dt) {
        if (!planetAlive) return;
        for (Human h : humans) {
            if (h.dead) continue;
            switch (h.state) {
                case H_WALK:
                    if (rnd.nextFloat() < dt * 0.25f) h.vx = -h.vx;
                    h.x = wrap(h.x + h.vx * dt);
                    h.y = GROUND;
                    break;
                case H_FALLING:
                    h.vy = Math.min(h.vy + 170 * dt, 230);
                    h.y += h.vy * dt;
                    if (alive && Math.abs(dxw(h.x, px)) < 52 && Math.abs(h.y - py) < 44) {
                        h.state = H_RESCUED;
                        carried = h;
                        addScore(500);
                        popup(px, py - 40, "500");
                        sound.play(sound.bonus);
                    } else if (h.y >= GROUND) {
                        h.y = GROUND;
                        if (GROUND - h.fallFrom > 320) {
                            h.dead = true;
                            explode(h.x, h.y, 0xFFCC88FF, 12, 150);
                            checkPlanet();
                        } else {
                            h.state = H_WALK;
                            addScore(250);
                            popup(h.x, h.y - 40, "250");
                        }
                    }
                    break;
                case H_RESCUED:
                    if (carried != h) { h.state = H_FALLING; h.fallFrom = h.y; break; }
                    h.x = px;
                    h.y = py + 40;
                    if (py >= GROUND - 64) {
                        h.state = H_WALK;
                        h.y = GROUND;
                        carried = null;
                        addScore(500);
                        popup(h.x, h.y - 50, "500");
                        sound.play(sound.bonus);
                    }
                    break;
            }
        }
    }

    void updateLasers(float dt) {
        for (Laser l : lasers) {
            l.life -= dt;
            l.head += 2800 * dt;
            if (l.life < 0.45f) l.tail += 2200 * dt;
            if (l.life <= 0 || l.tail >= l.head || l.head > VW * 1.2f) { l.dead = true; continue; }
            for (Enemy e : enemies) {
                if (e.dead) continue;
                if (Math.abs(e.y - l.y) > 32) continue;
                float d = dxw(e.x, l.x0) * l.dir;
                if (d >= l.tail - 10 && d <= l.head + 10) {
                    killEnemy(e, false);
                    l.dead = true;
                    break;
                }
            }
        }
        for (int i = lasers.size() - 1; i >= 0; i--) if (lasers.get(i).dead) lasers.remove(i);
    }

    void updateBullets(float dt) {
        for (int i = bullets.size() - 1; i >= 0; i--) {
            Bullet b = bullets.get(i);
            b.life -= dt;
            b.x = wrap(b.x + b.vx * dt);
            b.y += b.vy * dt;
            boolean hit = alive && invuln <= 0 && Math.abs(dxw(b.x, px)) < 40 && Math.abs(b.y - py) < 20;
            if (hit) killPlayer();
            if (hit || b.life <= 0 || b.y < PT || b.y > VH) {
                if (i < bullets.size()) bullets.remove(i);
            }
        }
    }

    void updateParticles(float dt) {
        for (int i = particles.size() - 1; i >= 0; i--) {
            Particle p = particles.get(i);
            p.life -= dt;
            if (p.life <= 0) { particles.remove(i); continue; }
            p.x = wrap(p.x + p.vx * dt);
            p.y += p.vy * dt;
            p.vx *= 0.98f;
            p.vy *= 0.98f;
        }
    }

    // =====================================================================
    // drawing
    // =====================================================================
    void fill(int c) { paint.setStyle(Paint.Style.FILL); paint.setColor(c); }

    void stroke(int c, float w) { paint.setStyle(Paint.Style.STROKE); paint.setColor(c); paint.setStrokeWidth(w); }

    void text(Canvas c, String s, float x, float y, float size, int col, Paint.Align a) {
        fill(col);
        paint.setTextSize(size);
        paint.setTextAlign(a);
        c.drawText(s, x, y, paint);
    }

    void draw2(Canvas c) {
        c.drawColor(0xFF000000);
        c.save();
        c.scale(scale, scale);

        // stars
        for (int i = 0; i < starX.length; i++) {
            float x = starX[i] * VW / 3000f;
            fill(starC[i]);
            c.drawRect(x, starY[i], x + 3, starY[i] + 3, paint);
        }

        drawTerrain(c);

        if (state != TITLE) {
            for (Human h : humans) if (!h.dead && planetAlive) drawHuman(c, h);
            for (Enemy e : enemies) if (!e.dead) drawEnemy(c, e);
            for (Bullet b : bullets) drawBullet(c, b);
            for (Laser l : lasers) drawLaser(c, l);
            if (alive && (state == PLAYING || state == WAVE_END)
                    && !(invuln > 0 && ((int) (invuln * 12)) % 2 == 0)) {
                drawShip(c, sx(px), py, facing, thrusting);
            }
        }
        for (Particle p : particles) {
            float s = sx(p.x);
            if (s < -10 || s > VW + 10) continue;
            int a = (int) (255 * Math.min(1f, p.life / p.max * 1.5f));
            fill((p.color & 0x00FFFFFF) | (a << 24));
            c.drawRect(s, p.y, s + p.size, p.y + p.size, paint);
        }
        for (Popup p : popups) text(c, p.text, sx(p.x), p.y, 34, 0xFFFFFF66, Paint.Align.CENTER);

        drawScanner(c);
        drawHud(c);

        switch (state) {
            case TITLE: drawTitle(c); break;
            case PLAYING:
                if (bannerTimer > 0) {
                    text(c, "WAVE " + wave, VW / 2, 420, 80, 0xFFFFFF00, Paint.Align.CENTER);
                    if (wave == 1)
                        text(c, "PROTECT THE HUMANOIDS", VW / 2, 490, 40, 0xFFCC88FF, Paint.Align.CENTER);
                }
                if (!alive && lives == 0)
                    text(c, "GAME OVER", VW / 2, 470, 90, 0xFFFF3333, Paint.Align.CENTER);
                drawControls(c);
                break;
            case WAVE_END:
                text(c, "WAVE " + wave + " COMPLETE", VW / 2, 400, 70, 0xFFFFFF00, Paint.Align.CENTER);
                text(c, planetAlive ? "HUMANOID BONUS  " + waveBonus : "NO HUMANOIDS LEFT",
                        VW / 2, 480, 44, 0xFFCC88FF, Paint.Align.CENTER);
                break;
            case GAME_OVER:
                text(c, "GAME OVER", VW / 2, 440, 100, 0xFFFF3333, Paint.Align.CENTER);
                text(c, "SCORE " + score + (score >= hiScore && score > 0 ? "   NEW HIGH SCORE!" : ""),
                        VW / 2, 520, 44, 0xFFFFFFFF, Paint.Align.CENTER);
                if (stateTimer <= 0)
                    text(c, "TAP TO CONTINUE", VW / 2, 600, 40, 0xFF88FF88, Paint.Align.CENTER);
                break;
        }
        if (paused)
            text(c, "PAUSED - TAP TO CONTINUE", VW / 2, 500, 56, 0xFFFFFF00, Paint.Align.CENTER);

        if (flash > 0) {
            int a = (int) (Math.min(1f, flash) * 160);
            fill((a << 24) | 0xFFFFFF);
            c.drawRect(0, 0, VW, VH, paint);
        }
        c.restore();
    }

    void drawTerrain(Canvas c) {
        if (!planetAlive && state != TITLE) return;
        float left = camC - VW / 2;
        int n = terrain.length;
        int i0 = (int) Math.floor(left / 30f) - 1;
        int i1 = i0 + (int) (VW / 30f) + 3;
        stroke(0xFFCC6622, 4);
        path.reset();
        boolean first = true;
        for (int i = i0; i <= i1; i++) {
            int k = ((i % n) + n) % n;
            float x = i * 30f - left;
            if (first) { path.moveTo(x, terrain[k]); first = false; }
            else path.lineTo(x, terrain[k]);
        }
        c.drawPath(path, paint);
        stroke(0xFF663311, 2);
        c.drawLine(0, GROUND + 14, VW, GROUND + 14, paint);
    }

    void drawShip(Canvas c, float x, float y, int f, boolean flame) {
        c.save();
        c.scale(SZ, SZ, x, y);
        drawShip0(c, x, y, f, flame);
        c.restore();
    }

    void drawShip0(Canvas c, float x, float y, int f, boolean flame) {
        if (flame) {
            fill(rnd.nextBoolean() ? 0xFFFF8800 : 0xFFFFDD00);
            path.reset();
            path.moveTo(x - f * 28, y - 8);
            path.lineTo(x - f * (46 + rnd.nextInt(22)), y + 2);
            path.lineTo(x - f * 28, y + 10);
            path.close();
            c.drawPath(path, paint);
        }
        path.reset();
        path.moveTo(x + f * 40, y + 5);
        path.lineTo(x + f * 10, y - 4);
        path.lineTo(x - f * 12, y - 17);
        path.lineTo(x - f * 30, y - 17);
        path.lineTo(x - f * 24, y + 2);
        path.lineTo(x - f * 30, y + 13);
        path.lineTo(x + f * 12, y + 13);
        path.close();
        fill(0xFFEEEEEE);
        c.drawPath(path, paint);
        fill(0xFFFF3399);
        c.drawRect(Math.min(x - f * 26, x + f * 8), y + 4, Math.max(x - f * 26, x + f * 8), y + 9, paint);
        fill(0xFF33CCFF);
        c.drawCircle(x - f * 2, y - 3, 5, paint);
    }

    void drawEnemy(Canvas c, Enemy e) {
        float x = sx(e.x), y = e.y;
        if (x < -80 || x > VW + 80) return;
        c.save();
        c.scale(SZ, SZ, x, y);
        drawEnemy0(c, e, x, y);
        c.restore();
    }

    void drawEnemy0(Canvas c, Enemy e, float x, float y) {
        switch (e.type) {
            case LANDER:
                stroke(0xFFFFDD33, 3);
                c.drawLine(x - 12, y + 6, x - 20, y + 20, paint);
                c.drawLine(x + 12, y + 6, x + 20, y + 20, paint);
                c.drawLine(x, y + 8, x, y + 20, paint);
                fill(0xFF33DD33);
                rect.set(x - 20, y - 10, x + 20, y + 10);
                c.drawOval(rect, paint);
                fill(0xFFFFDD33);
                rect.set(x - 9, y - 18, x + 9, y - 2);
                c.drawOval(rect, paint);
                break;
            case MUTANT: {
                boolean ph = ((int) (e.t * 10)) % 2 == 0;
                fill(ph ? 0xFFFF33FF : 0xFF33FF66);
                path.reset();
                path.moveTo(x, y - 18);
                path.lineTo(x + 20, y - 4);
                path.lineTo(x + 12, y + 16);
                path.lineTo(x, y + 6);
                path.lineTo(x - 12, y + 16);
                path.lineTo(x - 20, y - 4);
                path.close();
                c.drawPath(path, paint);
                fill(ph ? 0xFF33FF66 : 0xFFFF33FF);
                c.drawCircle(x, y - 3, 6, paint);
                break;
            }
            case BOMBER:
                c.save();
                c.rotate(e.t * 90, x, y);
                fill(0xFF3377FF);
                c.drawRect(x - 15, y - 15, x + 15, y + 15, paint);
                fill(0xFF000000);
                c.drawRect(x - 7, y - 7, x + 7, y + 7, paint);
                c.restore();
                break;
            case POD:
                fill(0xFF9933DD);
                c.drawCircle(x, y, 19, paint);
                fill(0xFFFF4444);
                for (int i = 0; i < 6; i++) {
                    double a = e.t * 2 + i * Math.PI / 3;
                    c.drawCircle(x + (float) Math.cos(a) * 11, y + (float) Math.sin(a) * 11, 3.5f, paint);
                }
                break;
            case SWARMER:
                fill(0xFFFF4444);
                path.reset();
                path.moveTo(x, y - 9);
                path.lineTo(x + 11, y);
                path.lineTo(x, y + 9);
                path.lineTo(x - 11, y);
                path.close();
                c.drawPath(path, paint);
                break;
            case BAITER:
                fill(0xFF99FF66);
                rect.set(x - 34, y - 8, x + 34, y + 8);
                c.drawOval(rect, paint);
                fill(0xFF226622);
                c.drawRect(x - 18, y - 3, x + 18, y + 3, paint);
                break;
        }
    }

    void drawHuman(Canvas c, Human h) {
        float x = sx(h.x), y = h.y;
        if (x < -20 || x > VW + 20) return;
        c.save();
        c.scale(1.25f, 1.25f, x, y);
        drawHuman0(c, h, x, y);
        c.restore();
    }

    void drawHuman0(Canvas c, Human h, float x, float y) {
        int col = h.state == H_FALLING ? (((int) (h.y / 20)) % 2 == 0 ? 0xFFFFFFFF : 0xFFCC88FF) : 0xFFCC88FF;
        fill(col);
        c.drawCircle(x, y - 22, 5, paint);
        stroke(col, 4);
        c.drawLine(x, y - 17, x, y - 6, paint);
        c.drawLine(x, y - 6, x - 5, y + 2, paint);
        c.drawLine(x, y - 6, x + 5, y + 2, paint);
        c.drawLine(x - 7, y - 13, x + 7, y - 13, paint);
    }

    void drawBullet(Canvas c, Bullet b) {
        float x = sx(b.x);
        if (x < -10 || x > VW + 10) return;
        if (b.mine) {
            fill(((int) (b.life * 8)) % 2 == 0 ? 0xFFFFFF33 : 0xFFFF8800);
            c.drawRect(x - 4, b.y - 4, x + 4, b.y + 4, paint);
        } else {
            fill(rnd.nextBoolean() ? 0xFFFFFFFF : 0xFFFF6666);
            c.drawRect(x - 4, b.y - 4, x + 4, b.y + 4, paint);
        }
    }

    void drawLaser(Canvas c, Laser l) {
        float a = sx(l.x0) + l.dir * l.tail, b = sx(l.x0) + l.dir * l.head;
        stroke(0xFFFFFFFF, 3);
        c.drawLine(a, l.y, b, l.y, paint);
        stroke(rnd.nextBoolean() ? 0xFF66FFFF : 0xFFFF66FF, 3);
        float mid = a + (b - a) * 0.6f;
        c.drawLine(a, l.y, mid, l.y, paint);
    }

    void drawScanner(Canvas c) {
        float x0 = VW * 0.27f, x1 = VW * 0.73f, w = x1 - x0, y0 = 8, y1 = 112, h = y1 - y0;
        fill(0xFF000000);
        c.drawRect(x0, y0, x1, y1, paint);
        stroke(0xFF2255FF, 3);
        c.drawRect(x0, y0, x1, y1, paint);
        stroke(0xFF2255FF, 3);
        c.drawLine(0, PT - 6, VW, PT - 6, paint);

        float centre = state == TITLE ? camC : px;
        if (planetAlive || state == TITLE) {
            stroke(0xFF884411, 2);
            path.reset();
            for (int i = 0; i <= 64; i++) {
                float wx = centre - W / 2 + i * W / 64f;
                int k = (int) (wrap(wx) / 30f) % terrain.length;
                float yy = y0 + (terrain[k] - PT) / (VH - PT) * h;
                float xx = x0 + i * w / 64f;
                if (i == 0) path.moveTo(xx, yy); else path.lineTo(xx, yy);
            }
            c.drawPath(path, paint);
        }
        if (state == TITLE) return;

        // visible window brackets
        float wl = x0 + w / 2 + dxw(camC - VW / 2, centre) / W * w;
        float wr = x0 + w / 2 + dxw(camC + VW / 2, centre) / W * w;
        stroke(0xFFFFFFFF, 2);
        c.drawLine(wl, y0 + 2, wl + 8, y0 + 2, paint);
        c.drawLine(wl, y1 - 2, wl + 8, y1 - 2, paint);
        c.drawLine(wr, y0 + 2, wr - 8, y0 + 2, paint);
        c.drawLine(wr, y1 - 2, wr - 8, y1 - 2, paint);

        if (planetAlive) {
            fill(0xFFCC88FF);
            for (Human hm : humans) {
                if (hm.dead) continue;
                float mx = x0 + w / 2 + dxw(hm.x, centre) / W * w;
                float my = y0 + (hm.y - PT) / (VH - PT) * h;
                c.drawRect(mx - 2, my - 4, mx + 2, my, paint);
            }
        }
        for (Enemy e : enemies) {
            if (e.dead) continue;
            float mx = x0 + w / 2 + dxw(e.x, centre) / W * w;
            float my = y0 + (e.y - PT) / (VH - PT) * h;
            fill(COL[e.type]);
            c.drawRect(mx - 3, my - 3, mx + 3, my + 3, paint);
        }
        if (alive) {
            fill(0xFFFFFFFF);
            float my = y0 + (py - PT) / (VH - PT) * h;
            c.drawRect(x0 + w / 2 - 4, my - 2, x0 + w / 2 + 4, my + 2, paint);
        }
    }

    void drawHud(Canvas c) {
        float lx = VW * 0.27f - 20;
        text(c, String.valueOf(score), lx, 52, 44, 0xFFFFFF33, Paint.Align.RIGHT);
        if (state != TITLE) {
            int show = Math.min(lives, 6);
            for (int i = 0; i < show; i++) {
                float x = lx - 22 - i * 46;
                fill(0xFFEEEEEE);
                path.reset();
                path.moveTo(x + 18, 88);
                path.lineTo(x - 14, 80);
                path.lineTo(x - 14, 96);
                path.close();
                c.drawPath(path, paint);
            }
            fill(0xFFFF6633);
            for (int i = 0; i < Math.min(bombs, 6); i++) {
                float x = lx - 10 - i * 22;
                c.drawRect(x - 7, 102, x + 7, 112, paint);
            }
        }
        float rx = VW * 0.73f + 20;
        text(c, "HI " + Math.max(hiScore, score), rx, 52, 36, 0xFF88FF88, Paint.Align.LEFT);
        if (state != TITLE) text(c, "WAVE " + wave, rx, 98, 32, 0xFF88CCFF, Paint.Align.LEFT);
    }

    void drawControls(Canvas c) {
        // movement stick
        if (stickId >= 0) {
            stroke(0x55FFFFFF, 4);
            c.drawCircle(stickAx, stickAy, 85, paint);
            fill(0x66FFFFFF);
            float dx = clamp(stickX - stickAx, -85, 85), dy = clamp(stickY - stickAy, -85, 85);
            c.drawCircle(stickAx + dx, stickAy + dy, 38, paint);
        } else {
            stroke(0x33FFFFFF, 3);
            c.drawCircle(180, VH - 200, 85, paint);
            text(c, "FLY", 180, VH - 190, 30, 0x55FFFFFF, Paint.Align.CENTER);
        }
        // buttons
        fill(fireHeld ? 0x88FF3355 : 0x44FF3355);
        c.drawCircle(fireX, fireY, fireR, paint);
        text(c, "FIRE", fireX, fireY + 12, 36, 0xAAFFFFFF, Paint.Align.CENTER);
        fill(bombHeld ? 0x88FF8800 : 0x44FF8800);
        c.drawCircle(bombX, bombY, smallR, paint);
        text(c, "BOMB", bombX, bombY + 10, 28, 0xAAFFFFFF, Paint.Align.CENTER);
        fill(hyperHeld ? 0x8833AAFF : 0x4433AAFF);
        c.drawCircle(hypX, hypY, smallR, paint);
        text(c, "HYPER", hypX, hypY + 10, 26, 0xAAFFFFFF, Paint.Align.CENTER);
        fill(revHeld ? 0x8833FF88 : 0x4433FF88);
        c.drawCircle(revX, revY, smallR, paint);
        text(c, "REV", revX, revY + 10, 30, 0xAAFFFFFF, Paint.Align.CENTER);
    }

    void drawTitle(Canvas c) {
        text(c, "PLANET GUARDIAN", VW / 2, 300, 110, 0xFFFF3399, Paint.Align.CENTER);
        text(c, "Stop the landers carrying off your humanoids", VW / 2, 390, 38, 0xFFCC88FF, Paint.Align.CENTER);
        int y = 480;
        text(c, "LEFT THUMB  drag anywhere to fly - push left/right to thrust and turn", VW / 2, y, 30, 0xFFDDDDDD, Paint.Align.CENTER);
        text(c, "RIGHT THUMB  FIRE (hold)   REV turns round   BOMB clears screen   HYPER warps", VW / 2, y + 46, 30, 0xFFDDDDDD, Paint.Align.CENTER);
        text(c, "Shoot a lander carrying a humanoid, then catch it and fly it back down", VW / 2, y + 92, 30, 0xFFDDDDDD, Paint.Align.CENTER);
        text(c, "Bonus ship and bomb every 10,000", VW / 2, y + 138, 30, 0xFFDDDDDD, Paint.Align.CENTER);

        float ix = VW / 2 - 330;
        int[] types = {LANDER, MUTANT, BOMBER, POD, SWARMER, BAITER};
        Enemy demo = new Enemy();
        for (int i = 0; i < types.length; i++) {
            demo.type = types[i];
            demo.t = System.nanoTime() / 1e9f;
            demo.x = wrap(camC - VW / 2 + ix + i * 132);
            demo.y = 720;
            drawEnemy(c, demo);
            text(c, String.valueOf(POINTS[types[i]]), ix + i * 132, 790, 26, COL[types[i]], Paint.Align.CENTER);
        }
        if (((System.nanoTime() / 500_000_000L) % 2) == 0)
            text(c, "TAP TO START", VW / 2, 870, 56, 0xFFFFFF33, Paint.Align.CENTER);
    }
}
