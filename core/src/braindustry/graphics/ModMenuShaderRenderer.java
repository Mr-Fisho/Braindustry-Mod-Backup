package braindustry.graphics;

import arc.Core;
import arc.files.Fi;
import arc.func.Floatc2;
import arc.graphics.Camera;
import arc.graphics.Color;
import arc.graphics.Pixmap;
import arc.graphics.PixmapIO;
import arc.graphics.g2d.*;
import arc.graphics.gl.FrameBuffer;
import arc.graphics.gl.Shader;
import arc.math.Angles;
import arc.math.Mat;
import arc.math.Mathf;
import arc.scene.ui.layout.Scl;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.*;
import arc.util.noise.Ridged;
import arc.util.noise.Simplex;
import braindustry.BDVars;
import braindustry.cfunc.BackgroundUnitData;
import braindustry.cfunc.BackgroundUnitMovingType;
import braindustry.content.Blocks.ModBlocks;
import braindustry.content.ModUnitTypes;
import braindustry.gen.BackgroundSettings;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.UnitTypes;
import mindustry.game.Team;
import mindustry.graphics.CacheLayer;
import mindustry.graphics.Layer;
import mindustry.graphics.Pal;
import mindustry.graphics.Trail;
import mindustry.type.UnitType;
import mindustry.world.Block;
import mindustry.world.CachedTile;
import mindustry.world.Tile;
import mindustry.world.Tiles;
import mindustry.world.blocks.environment.Floor;
import mindustry.world.blocks.environment.OreBlock;

import java.util.Arrays;

import static mindustry.Vars.*;

public class ModMenuShaderRenderer {
    private static final float darkness = 0.3F;
    private final int width;
    private final int height;
    boolean errorred = false;
    private int[] cacheLayers;
    private int cacheWall;
    private Camera camera;
    private Mat mat;
    private FrameBuffer shadows;
    private CacheBatch batch;
    private float time;
    private float flyerRot;
    private int flyers;
    private @Nullable
    UnitType flyerType;
    private BackgroundUnitData[] flyersData;

    public ModMenuShaderRenderer() {
        this.width = !Vars.mobile ? 100 : 60;
        this.height = !Vars.mobile ? 50 : 40;
        buildMain(true);
    }

    public static Shader createShader() {
        return new Shader("attribute vec4 a_position;\nattribute vec4 a_color;\nattribute vec2 a_texCoord0;\nattribute vec4 a_mix_color;\nuniform mat4 u_projTrans;\nvarying vec4 v_color;\nvarying vec4 v_mix_color;\nvarying vec2 v_texCoords;\n\nvoid main(){\n   v_color = a_color;\n   v_color.a = v_color.a * (255.0/254.0);\n   v_mix_color = a_mix_color;\n   v_mix_color.a *= (255.0/254.0);\n   v_texCoords = a_texCoord0;\n   gl_Position = u_projTrans * a_position;\n}",
                "\nvarying lowp vec4 v_color;\nvarying lowp vec4 v_mix_color;\nvarying highp vec2 v_texCoords;\nuniform highp sampler2D u_texture;\n\nvoid main(){\n  vec4 c = texture2D(u_texture, v_texCoords);\n  gl_FragColor = v_color * mix(c, vec4(v_mix_color.rgb, c.a), v_mix_color.a);\n}");
    }

    public static float darkness() {
        return darkness;
    }

    public void takeBackgroundScreenshot() {
        int aFloat = (int) BDVars.settings.getFloat("background.screenshot.scl");
        int w = width * tilesize * aFloat, h = height * tilesize * aFloat;
        int memory = w * h * 4 / 1024 / 1024;

        if (memory >= 65) {
            ui.showInfo("@screenshot.invalid");
            return;
        }
        FrameBuffer buffer = new FrameBuffer(w, h);
        float lastDelta = Time.delta;
        Time.delta = 0;
        render();
        float vpW = camera.width, vpH = camera.height, px = camera.position.x, py = camera.position.y;
        disableUI = true;
        camera.width = w;
        camera.height = h;
        camera.position.x = w / 2f + tilesize / 2f;
        camera.position.y = h / 2f + tilesize / 2f;
        buffer.begin();
        render();
        buffer.end();
        disableUI = false;
        camera.width = vpW;
        camera.height = vpH;
        camera.position.set(px, py);
        buffer.begin();
        byte[] lines = ScreenUtils.getFrameBufferPixels(0, 0, w, h, true);
        for (int i = 0; i < lines.length; i += 4) {
            lines[i + 3] = (byte) 255;
        }
        buffer.end();
        Pixmap fullPixmap = new Pixmap(w, h);
        Buffers.copy(lines, 0, fullPixmap.getPixels(), lines.length);
        Fi file = screenshotDirectory.child("screenshot-background-" + Time.millis() + ".png");
        PixmapIO.writePng(file, fullPixmap);
        fullPixmap.dispose();
        ui.showInfoFade(Core.bundle.format("screenshot", file.toString()));

        buffer.dispose();
        Time.delta = lastDelta;
    }

    public void rebuild() {
        batch.dispose();
        shadows.dispose();
        shadows = null;
        batch = null;
        buildMain(false);
    }

    protected void buildMain(boolean timeMark) {
        if (cacheLayers == null || cacheLayers.length != CacheLayer.all.length) {
            cacheLayers = null;
            cacheLayers = new int[CacheLayer.all.length];
        }
        this.camera = new Camera();
        this.mat = new Mat();
        Mathf.rand.setSeed(BackgroundSettings.useSeed() && BackgroundSettings.useStyles() ? BackgroundSettings.seed() : System.nanoTime());
        this.time = 0.0F;
        this.flyerRot = 45.0F;
        this.flyers = Mathf.chance(0.2D) ? Mathf.random(35) : Mathf.random(15);
        if (flyersData != null) {
            BackgroundUnitData[] unitData = new BackgroundUnitData[flyers];
            for (int i = 0; i < unitData.length; i++) {
                if (i < flyersData.length) {
                    unitData[i] = flyersData[i];
                    unitData[i].reset();
                } else {
                    unitData[i] = new BackgroundUnitData();
                }
            }
            for (int i = 0; i < flyersData.length; i++) {
              if (i>unitData.length)  flyersData[i].clear();
                flyersData[i] = null;
            }
            flyersData = null;
            flyersData = unitData;
        } else {
            flyersData = new BackgroundUnitData[flyers];
            for (int i = 0; i < flyersData.length; i++) {
                flyersData[i] = new BackgroundUnitData();
            }
        }
        this.flyerType = Structs.select(
                UnitTypes.flare, UnitTypes.flare,
                UnitTypes.horizon, UnitTypes.mono,
                UnitTypes.poly, UnitTypes.mega,
                UnitTypes.zenith,
                ModUnitTypes.armor, ModUnitTypes.armor,
                ModUnitTypes.chainmail, ModUnitTypes.chestplate);

        if (BackgroundSettings.useStyles()) {
            UnitType unit = BackgroundSettings.unit();
            if (unit != null) flyerType = unit;
            if (!BackgroundSettings.hasUnits()) {
                flyerType = null;
            }
        }
        if (timeMark) Time.mark();
        this.generate();
        this.cache();
        if (timeMark) Log.info("Time to generate menu: @", Time.elapsed());
    }

    private void generate() {
        Vars.world.beginMapLoad();
        Tiles tiles = Vars.world.resize(this.width, this.height);
        Seq<Block> ores = Vars.content.blocks().select((b) -> {
            return b instanceof OreBlock;
        });
        this.shadows = new FrameBuffer(this.width, this.height);
        int offset = Mathf.random(100000);
        int s1 = offset, s2 = offset + 1, s3 = offset + 2;
//        Rand last = Mathf.rand;
//        Mathf.rand=new Rand(System.nanoTime());
        Block[] selected = {null, null};
        Block[] selected2 = {null, null};

        boolean useWalls = true;
        Block floord3 = null, floord4 = null;
        if (BackgroundSettings.useStyles()) {
            useWalls = BackgroundSettings.useWalls();
            if (!useWalls) {
                try {
                    floord3 = BackgroundSettings.floor3();
                    floord4 = BackgroundSettings.floor4();
                } catch (Exception ignored) {
                }
                useWalls = floord3 == null || floord4 == null;
            }
            try {
                selected = new Block[]{BackgroundSettings.floor1(), BackgroundSettings.wall1()};
                selected2 = new Block[]{BackgroundSettings.floor2(), BackgroundSettings.wall2()};
            } catch (Exception ignored) {
            }

        }
        if (selected[0] == null || selected[1] == null) {
            selected = Structs.select(new Block[][]{
                    {Blocks.sand, Blocks.sandWall},
                    {Blocks.shale, Blocks.shaleWall},
                    {Blocks.ice, Blocks.iceWall},
                    {ModBlocks.obsidianFloor, ModBlocks.obsidianBlock},
                    {Blocks.sand, Blocks.sandWall},
                    {Blocks.shale, Blocks.shaleWall},
                    {Blocks.ice, Blocks.iceWall},
                    {ModBlocks.obsidianFloor, ModBlocks.obsidianBlock},
                    {Blocks.moss, Blocks.sporePine},
            });
        }
        if (selected2[0] == null || selected2[1] == null) {
            selected2 = Structs.select(new Block[][]{
                    {Blocks.basalt, Blocks.duneWall},
                    {Blocks.basalt, Blocks.duneWall},
                    {Blocks.stone, Blocks.stoneWall},
                    {Blocks.stone, Blocks.stoneWall},
                    {Blocks.moss, Blocks.sporeWall},
                    {Blocks.salt, Blocks.saltWall},
                    {ModBlocks.jungleFloor, ModBlocks.jungleWall},
                    {ModBlocks.jungleFloor, ModBlocks.jungleWall},
                    {ModBlocks.crimzesFloor, ModBlocks.crimzesWall},
                    {Blocks.water, Blocks.sandWall},

            });
        }
        Block ore1 = ores.random();
        ores.remove(ore1);
        Block ore2 = ores.random();
        double tr1 = (double) Mathf.random(0.65F, 0.85F);
        double tr2 = (double) Mathf.random(0.65F, 0.85F);
        boolean doheat = Mathf.chance(0.25D);
        boolean tendrils = Mathf.chance(0.25D);
        boolean tech = Mathf.chance(0.25D);
        int secSize = 10;
        Block floord = selected[0];
        Block walld = selected[1];
        Block floord2 = selected2[0];
        Block walld2 = selected2[1];

        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                Block floor = floord;
                Block ore = Blocks.air;
                Block wall = Blocks.air;

                if (Simplex.noise2d(s1, 3, 0.5, 1 / 20.0, x, y) > 0.5) {
                    if (useWalls) {
                        wall = walld;
                    } else {
                        floor = floord3;
                    }
                }

                if (Simplex.noise2d(s3, 3, 0.5, 1 / 20.0, x, y) > 0.5) {
                    if (!useWalls && floor != floord) {
                        floor = floord4;
                    } else
                        floor = floord2;
                    if (wall != Blocks.air && useWalls) {
                        wall = walld2;
                    }
                }

                if (Simplex.noise2d(s2, 3, 0.3, 1 / 30.0, x, y) > tr1) {
                    ore = ore1;
                }

                if (Simplex.noise2d(s2, 2, 0.2, 1 / 15.0, x, y + 99999) > tr2) {
                    ore = ore2;
                }

                if (doheat) {
                    double heat = Simplex.noise2d(s3, 4, 0.6, 1 / 50.0, x, y + 9999);
                    double base = 0.65;

                    if (heat > base) {
                        ore = Blocks.air;
                        wall = Blocks.air;
                        floor = Blocks.basalt;

                        if (heat > base + 0.1) {
                            floor = Blocks.hotrock;

                            if (heat > base + 0.15) {
                                floor = Blocks.magmarock;
                            }
                        }
                    }
                }

                if (tech) {
                    int mx = x % secSize, my = y % secSize;
                    int sclx = x / secSize, scly = y / secSize;
                    if (Simplex.noise2d(s1, 2, 1f / 10f, 0.5f, sclx, scly) > 0.4f && (mx == 0 || my == 0 || mx == secSize - 1 || my == secSize - 1)) {
                        floor = Blocks.darkPanel3;
                        if (Mathf.dst(mx, my, secSize / 2, secSize / 2) > secSize / 2f + 1) {
                            floor = Blocks.darkPanel4;
                        }


                        if (wall != Blocks.air && Mathf.chance(0.7)) {
                            wall = Blocks.darkMetal;
                        }
                    }
                }

                if (tendrils) {
                    if (Ridged.noise2d(1 + offset, x, y, 1f / 17f) > 0f) {
                        floor = Mathf.chance(0.2) ? Blocks.sporeMoss : Blocks.moss;

                        if (wall != Blocks.air) {
                            wall = Blocks.sporeWall;
                        }
                    }
                }

                Tile tile;
                tiles.set(x, y, (tile = new CachedTile()));
                tile.x = (short) x;
                tile.y = (short) y;
                tile.setFloor(floor.asFloor());
                tile.setBlock(wall);
                tile.setOverlay(ore);
            }
        }

        Vars.world.endMapLoad();
    }

    private void cache() {
        Arrays.fill(cacheLayers, -1);
        //draw shadows
        Draw.proj().setOrtho(0, 0, shadows.getWidth(), shadows.getHeight());
        shadows.begin(Color.clear);
        Draw.color(Color.black);

        for (Tile tile : world.tiles) {
            if (tile.block() != Blocks.air) {
                Fill.rect(tile.x + 0.5f, tile.y + 0.5f, 1, 1);
            }
        }

        Draw.color();
        shadows.end();

        Batch prev = Core.batch;

        Core.batch = batch = new CacheBatch(new SpriteCache(width * height * 6, false));
        ObjectSet<CacheLayer> used = new ObjectSet<>();
        for (int tilex = 0; tilex < world.width(); tilex++) {
            for (int tiley = 0; tiley < world.height(); tiley++) {
                Tile tile = world.rawTile(tilex, tiley);
                boolean wall = tile.block().cacheLayer != CacheLayer.normal;

                if (wall) {
                    used.add(tile.block().cacheLayer);
                }

                if (!wall || world.isAccessible(tilex, tiley)) {
                    used.add(tile.floor().cacheLayer);
                }
            }
        }
        for (CacheLayer layer : used) {
            batch.beginCache();
            for (int tilex = 0; tilex < world.width(); tilex++) {
                for (int tiley = 0; tiley < world.height(); tiley++) {
                    Tile tile = world.tile(tilex, tiley);
                    Floor floor;

                    if (tile == null) {
                        continue;
                    } else {
                        floor = tile.floor();
                    }

                    if (tile.block().cacheLayer == layer && layer == CacheLayer.walls && !(tile.isDarkened() && tile.data >= 5)) {
                        tile.block().drawBase(tile);
                    } else if (floor.cacheLayer == layer && (world.isAccessible(tile.x, tile.y) || tile.block().cacheLayer != CacheLayer.walls || !tile.block().fillsTile)) {
                        floor.drawBase(tile);
                    } else if (floor.cacheLayer != layer && layer != CacheLayer.walls) {
                        floor.drawNonLayer(tile, layer);
                    }
                }
            }
            cacheLayers[layer.id] = batch.endCache();
        }

        batch.beginCache();

        for (Tile tile : world.tiles) {
            tile.block().drawBase(tile);
        }

        cacheWall = batch.endCache();

        Core.batch = prev;
    }

    public void render() {
        time += Time.delta;
        float scaling = Math.max(Scl.scl(4f), Math.max(Core.graphics.getWidth() / ((width - 1f) * tilesize), Core.graphics.getHeight() / ((height - 1f) * tilesize)));
        camera.position.set(width * tilesize / 2f, height * tilesize / 2f);
        camera.resize(Core.graphics.getWidth() / scaling,
                Core.graphics.getHeight() / scaling);

        mat.set(Draw.proj());
        Draw.flush();
        Draw.proj(camera);
        batch.setProjection(camera.mat);
        float prev = Time.time;
        Time.time = time;
        Draw.z(Layer.floor);
        for (int i = 0; i < cacheLayers.length; i++) {
            if (cacheLayers[i] == -1) continue;
            CacheLayer.all[i].begin();
            batch.beginDraw();
            batch.drawCache(cacheLayers[i]);
            batch.endDraw();
            CacheLayer.all[i].end();
        }
        Time.time = prev;
        Draw.color();
        Draw.z(Layer.block - 1);
        Draw.rect(Draw.wrap(shadows.getTexture()),
                width * tilesize / 2f - 4f, height * tilesize / 2f - 4f,
                width * tilesize, -height * tilesize);
        Draw.flush();
        Draw.z(Layer.block);
        batch.beginDraw();
        batch.drawCache(cacheWall);
        batch.endDraw();
        Draw.z(Layer.flyingUnitLow);
        drawFlyers();

        Draw.z(Layer.darkness);
        Draw.proj(mat);
        Draw.color(0f, 0f, 0f, darkness);
        Fill.crect(0, 0, Core.graphics.getWidth(), Core.graphics.getHeight());
        Draw.color();
    }

    private void drawFlyers() {
        if (flyerType == null) return;
        TextureRegion icon = this.flyerType.fullIcon;
        TextureRegion outline = this.flyerType.outlineRegion;
        TextureRegion cellRegion = this.flyerType.cellRegion;
        float size = (float) Math.max(icon.width, icon.height) * Draw.scl * 1.6F;
        int[] i= {0};
        BackgroundUnitMovingType movingType = BackgroundSettings.unitMovingType();
        if (movingType==BackgroundUnitMovingType.naval) {
            flyers((x,y)->{
                Trail tleft = flyersData[i[0]].tleft;
                Trail tright = flyersData[i[0]].tright;
                Color trailColor = flyersData[i[0]].trailColor;
                i[0]++;

                Tmp.v1.trns(flyerRot, time * (2.0F + flyerType.speed));
                for (int i2 = 0; i2 < 2; i2++) {
                    Trail t = i2 == 0 ? tleft : tright;
                    t.length = flyerType.trailLength;
                    int sign = i2 == 0 ? -1 : 1;
                    float cx = Angles.trnsx(flyerRot - 90, flyerType.trailX * sign, flyerType.trailY) + x, cy = Angles.trnsy(flyerRot - 90, flyerType.trailX * sign, flyerType.trailY) + y;
                    t.update(cx, cy, world.floorWorld(cx, cy).isLiquid ? 1 : 0);
                }
                float z = Draw.z();
                Draw.z(Layer.debris);
                Tile tileOn = world.tileWorld(x, y);
                Floor floor = tileOn == null ? Blocks.air.asFloor() : tileOn.floor();
                Color color = Tmp.c1.set(floor.mapColor.equals(Color.black) ? Blocks.water.mapColor : floor.mapColor).mul(1.5f);
                trailColor.lerp(color, Mathf.clamp(Time.delta * 0.04f));
                tleft.draw(trailColor, flyerType.trailScl);
                tright.draw(trailColor, flyerType.trailScl);
                Draw.z(z);
            });
        }
        if (movingType==BackgroundUnitMovingType.flying){
            flyers((x, y) -> {
                Draw.color(0.0F, 0.0F, 0.0F, 0.4F);
                Draw.rect(outline, x - 12.0F, y - 13.0F, this.flyerRot - 90.0F);
                Draw.rect(icon, x - 12.0F, y - 13.0F, this.flyerRot - 90.0F);
            });
        }
        this.flyers((x, y) -> {
            Draw.color(0.0F, 0.0F, 0.0F, 0.4F);
            Draw.rect("circle-shadow", x, y, size, size);
        });
        Draw.color();
        this.flyers((x, y) -> {
            float engineOffset = this.flyerType.engineOffset;
            float engineSize = this.flyerType.engineSize;
            float rotation = this.flyerRot;
            if (movingType==BackgroundUnitMovingType.flying){
                Draw.color(Pal.engine);
                Fill.circle(x + Angles.trnsx(rotation + 180.0F, engineOffset), y + Angles.trnsy(rotation + 180.0F, engineOffset), engineSize + Mathf.absin(Time.time, 2.0F, engineSize / 4.0F));
                Draw.color(Color.white);
                Fill.circle(x + Angles.trnsx(rotation + 180.0F, engineOffset - 1.0F), y + Angles.trnsy(rotation + 180.0F, engineOffset - 1.0F), (engineSize + Mathf.absin(Time.time, 2.0F, engineSize / 4.0F)) / 2.0F);
            }
            Draw.color();
            Draw.rect(outline, x, y, this.flyerRot - 90.0F);
            Draw.rect(icon, x, y, this.flyerRot - 90.0F);
            Draw.color(Team.sharded.color);
            Draw.rect(cellRegion, x, y, this.flyerRot - 90.0F);
        });
    }

    private void flyers(Floatc2 cons) {
        if (flyerType == null) return;
        float tw = (float) (width * 8) * 1.0F + 8.0F;
        float th = (float) (height * 8) * 1.0F + 8.0F;
        float range = 500.0F;
        float offset = -100.0F;
        for (int i = 0; i < flyers; ++i) {
            Tmp.v1.trns(flyerRot, time * (2.0F + flyerType.speed));
            float x = (Mathf.randomSeedRange((long) i, range) + Tmp.v1.x + Mathf.absin(time + Mathf.randomSeedRange((long) (i + 2), 500.0F), 10.0F, 3.4F) + offset) % (tw + (float) Mathf.randomSeed((long) (i + 5), 0, 500));
            float y = (Mathf.randomSeedRange((long) (i + 1), range) + Tmp.v1.y + Mathf.absin(time + Mathf.randomSeedRange((long) (i + 3), 500.0F), 10.0F, 3.4F) + offset) % th;

            cons.get(x, y);
        }

    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public float time() {
        return time;
    }

    public float flyerRot() {
        return flyerRot;
    }

    public int flyers() {
        return flyers;
    }

    public UnitType flyerType() {
        return flyerType;
    }

    public Object[] flyersData() {
        return flyersData;
    }

    public void dispose() {
        this.batch.dispose();
        this.shadows.dispose();
    }
}
