package braindustry;

import ModVars.modVars;
import arc.Core;
import arc.graphics.g2d.TextureRegion;
import arc.struct.Seq;
import arc.util.CommandHandler;
import arc.util.Log;
import braindustry.audio.ModAudio;
import braindustry.core.ModContentLoader;
import braindustry.gen.*;
import braindustry.graphics.ModShaders;
import braindustry.input.ModDesktopInput;
import braindustry.input.ModMobileInput;
import mindustry.Vars;
import mindustry.ctype.Content;
import mindustry.ctype.MappableContent;
import mindustry.ctype.UnlockableContent;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.input.DesktopInput;
import mindustry.input.MobileInput;
import mindustry.mod.Mod;

import static ModVars.modFunc.*;
import static ModVars.modVars.*;
import static mindustry.Vars.*;

public class BraindustryMod extends Mod {


    public BraindustryMod() {
        Log.info("javaHeap: " + Core.app.getJavaHeap());
        ModEntityMapping.mapClasses();
        ModCall.registerPackets();
        Log.info("app: @\t@", Core.app, Core.app != null ? Core.app.getClass().getSimpleName() : null);
        modInfo = Vars.mods.getMod(this.getClass());
        modVars.load();
        ModListener.addRun(() -> {
            boolean modMobile = (control.input instanceof ModMobileInput);
            boolean modDesktop = (control.input instanceof ModDesktopInput);
            boolean mobile = (control.input instanceof MobileInput);
            boolean desktop = (control.input instanceof DesktopInput);
            if (mobile && !modMobile) control.setInput(new ModMobileInput());
            if (desktop && !modDesktop) control.setInput(new ModDesktopInput());
        });
        EventOn(ClientLoadEvent.class, (e) -> {
            ModAudio.reload();
        });
        Log.info("javaHeap: " + Core.app.getJavaHeap());
    }

    public static TextureRegion getIcon() {
        if (modInfo == null || modInfo.iconTexture == null) return Core.atlas.find("nomap");
        return new TextureRegion(modInfo.iconTexture);
    }

    public static boolean inPackage(String packageName, Object obj) {
        if (packageName == null || obj == null) return false;
        String name;
        try {
            name = obj.getClass().getPackage().getName();
        } catch (Exception e) {
            return false;
        }
        return name.startsWith(packageName + ".");
    }

    @Override
    public void registerServerCommands(CommandHandler handler) {
        modVars.netServer.registerCommands(handler);
    }

    @Override
    public void registerClientCommands(CommandHandler handler) {
        modVars.netClient.registerCommands(handler);
    }

    public void init() {
        Log.info("init javaHeap: " + Core.app.getJavaHeap());
        if (!loaded) return;
        Seq<Content> all = Seq.with(content.getContentMap()).<Content>flatten().select(c -> c.minfo.mod == modInfo).as();
        for (Content c : all) {
            if (inPackage("braindustry", c)) {
                if (c instanceof UnlockableContent) checkTranslate((UnlockableContent) c);
                if (c instanceof MappableContent && !headless) ModContentRegions.loadRegions((MappableContent) c);
            }
        }
        if (neededInit) listener.init();
        Log.info("init javaHeap: " + Core.app.getJavaHeap());
    }

    public void loadContent() {
        Log.info("loadContent javaHeap: " + Core.app.getJavaHeap());
        modInfo = Vars.mods.getMod(this.getClass());
        ModAudio.load();
        print("loading mod content...");
        modAssets.init();
        inTry(() -> {
            if (!headless) ModShaders.init();
        });
        if (!headless) {
            try {
                ModSounds.load();
                ModMusics.load();
            } catch (Exception ignored) {
            }
        }
        new ModContentLoader((load) -> {
            try {
                load.load();
            } catch (NullPointerException e) {
                if (!headless) showException(e);
            }
        });
        loaded = true;
        Log.info("loadContent javaHeap: " + Core.app.getJavaHeap());
    }
}
