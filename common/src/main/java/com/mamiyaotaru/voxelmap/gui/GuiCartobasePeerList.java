package com.mamiyaotaru.voxelmap.gui;

import com.mamiyaotaru.voxelmap.VoxelConstants;
import com.mamiyaotaru.voxelmap.chunksync.CartobaseClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

class GuiCartobasePeerList extends AbstractSelectionList<GuiCartobasePeerList.PeerRow> {
    private final GuiCartobasePeers parent;
    private final int rowLeft;
    private final int rowWidth;

    GuiCartobasePeerList(GuiCartobasePeers parent, int left, int top, int width, int height) {
        super(VoxelConstants.getMinecraft(), width, height, top, 22);
        this.parent = parent;
        this.rowLeft = left;
        this.rowWidth = width - 8;
        setX(left);
    }

    void setShares(CartobaseClient.ShareList shares) {
        clearEntries();
        if (shares == null) {
            return;
        }
        for (CartobaseClient.Share share : shares.incoming()) {
            addEntry(new PeerRow(share, "options.voxelmap.sharing.cartobaseIncoming", true));
        }
        for (CartobaseClient.Share share : shares.active()) {
            addEntry(new PeerRow(share, "options.voxelmap.sharing.cartobaseActive", false));
        }
        for (CartobaseClient.Share share : shares.outgoing()) {
            addEntry(new PeerRow(share, "options.voxelmap.sharing.cartobaseOutgoing", false));
        }
    }

    @Override
    public int getRowWidth() {
        return rowWidth;
    }

    @Override
    protected int scrollBarX() {
        return rowLeft + rowWidth + 4;
    }

    @Override
    public void updateWidgetNarration(NarrationElementOutput output) {
    }

    class PeerRow extends AbstractSelectionList.Entry<PeerRow> {
        private final CartobaseClient.Share share;
        private final String stateKey;
        private final Button acceptButton;
        private final Button removeButton;

        PeerRow(CartobaseClient.Share share, String stateKey, boolean incoming) {
            this.share = share;
            this.stateKey = stateKey;
            this.acceptButton = incoming
                    ? new Button.Builder(Component.translatable("options.voxelmap.sharing.cartobaseAccept"), button -> parent.acceptShare(share))
                            .bounds(0, 0, 76, 18).build()
                    : null;
            this.removeButton = new Button.Builder(Component.translatable("options.voxelmap.sharing.cartobaseRemove"), button -> parent.removeShare(share))
                    .bounds(0, 0, 76, 18).build();
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float tickDelta) {
            int y = getY() + 2;
            graphics.text(parent.getFont(), share.peerUsername(), rowLeft + 6, y + 5, 0xFFFFFFFF, false);
            graphics.text(parent.getFont(), Component.translatable(stateKey).getString(), rowLeft + 160, y + 5, 0xFFA0A0A0, false);
            if (acceptButton != null) {
                acceptButton.setPosition(rowLeft + rowWidth - 164, y);
                acceptButton.extractRenderState(graphics, mouseX, mouseY, tickDelta);
            }
            removeButton.setPosition(rowLeft + rowWidth - 84, y);
            removeButton.extractRenderState(graphics, mouseX, mouseY, tickDelta);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            return (acceptButton != null && acceptButton.mouseClicked(event, doubleClick))
                    || removeButton.mouseClicked(event, doubleClick);
        }

        @Override
        public boolean mouseReleased(MouseButtonEvent event) {
            return (acceptButton != null && acceptButton.mouseReleased(event))
                    || removeButton.mouseReleased(event);
        }
    }
}
