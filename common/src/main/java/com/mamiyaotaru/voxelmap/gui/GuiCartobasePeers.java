package com.mamiyaotaru.voxelmap.gui;

import com.mamiyaotaru.voxelmap.chunksync.CartobaseClient;
import com.mamiyaotaru.voxelmap.chunksync.ChunkShareConfig;
import com.mamiyaotaru.voxelmap.chunksync.RemoteSyncService;
import com.mamiyaotaru.voxelmap.gui.overridden.GuiScreenMinimap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class GuiCartobasePeers extends GuiScreenMinimap {
    private GuiCartobasePeerList list;
    private EditBox usernameBox;
    private volatile String status = "";

    public GuiCartobasePeers(Screen parent) {
        super();
        this.lastScreen = parent;
    }

    @Override
    protected void init() {
        clearWidgets();
        int listWidth = Math.min(width - 40, 520);
        int listX = (width - listWidth) / 2;
        usernameBox = new EditBox(getFont(), width / 2 - 154, 36, 200, 20,
                Component.translatable("options.voxelmap.sharing.cartobasePeerName"));
        usernameBox.setMaxLength(50);
        addRenderableWidget(usernameBox);
        addRenderableWidget(Button.builder(Component.translatable("options.voxelmap.sharing.cartobaseRequest"), button -> requestShare())
                .bounds(width / 2 + 54, 36, 100, 20).build());
        list = new GuiCartobasePeerList(this, listX, 66, listWidth, Math.max(60, height - 106));
        addRenderableWidget(list);
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(width / 2 - 100, height - 27, 200, 20).build());
        refresh();
    }

    void refresh() {
        String url = ChunkShareConfig.getCartobaseUrl();
        String session = ChunkShareConfig.getCartobaseSession();
        if (url == null || session == null) {
            status = Component.translatable("options.voxelmap.sharing.cartobaseNotLoggedIn").getString();
            list.setShares(null);
            return;
        }
        status = "...";
        runAsync(() -> {
            CartobaseClient.ShareList shares = new CartobaseClient(url, session).listShares();
            Minecraft.getInstance().execute(() -> {
                status = "";
                list.setShares(shares);
            });
        });
    }

    private void requestShare() {
        String name = usernameBox.getValue().trim();
        if (name.isEmpty()) {
            return;
        }
        usernameBox.setValue("");
        withClient(client -> {
            client.requestShare(name);
            afterAction();
        });
    }

    void acceptShare(CartobaseClient.Share share) {
        withClient(client -> {
            client.acceptShare(share.id());
            afterAction();
        });
    }

    void removeShare(CartobaseClient.Share share) {
        withClient(client -> {
            client.deleteShare(share.id());
            afterAction();
        });
    }

    private void afterAction() {
        RemoteSyncService.instance().refreshSharesNow(null);
        Minecraft.getInstance().execute(this::refresh);
    }

    private interface ClientTask {
        void run(CartobaseClient client) throws Exception;
    }

    private void withClient(ClientTask task) {
        String url = ChunkShareConfig.getCartobaseUrl();
        String session = ChunkShareConfig.getCartobaseSession();
        if (url == null || session == null) {
            status = Component.translatable("options.voxelmap.sharing.cartobaseNotLoggedIn").getString();
            return;
        }
        runAsync(() -> task.run(new CartobaseClient(url, session)));
    }

    private void runAsync(ThrowingRunnable task) {
        Thread thread = new Thread(() -> {
            try {
                task.run();
            } catch (Exception e) {
                Minecraft.getInstance().execute(() -> status = e.getMessage() == null ? "request failed" : e.getMessage());
            }
        }, "VoxelMap cartobase-peers");
        thread.setDaemon(true);
        thread.start();
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(getFont(), Component.translatable("options.voxelmap.sharing.cartobasePeersTitle"), width / 2, 14, 0xFFFFFFFF);
        if (!status.isEmpty()) {
            graphics.centeredText(getFont(), Component.literal(status), width / 2, height - 44, 0xFFFFA0A0);
        }
        if (list != null && list.children().isEmpty() && status.isEmpty()) {
            graphics.centeredText(getFont(), Component.translatable("options.voxelmap.sharing.cartobaseNoPeers"), width / 2, height / 2, 0xFFA0A0A0);
        }
    }
}
