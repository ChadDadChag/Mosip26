package io.mosip.registration.controller.liveness;

import javafx.geometry.HPos;
import javafx.geometry.NodeOrientation;
import javafx.geometry.VPos;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Ellipse;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.Shape;

/**
 * The camera card of the liveness screen ({@code docs/ui-liveness-spec.md} §9): the mirrored preview in a 4:3 card
 * with rounded corners, a 40% scrim outside the face oval, the oval in the state colour and the state badge on the
 * oval's top centre. The card is the largest centred 4:3 rectangle in the space this region is given, so it shrinks
 * when the screen needs room (the tips). Always laid out left to right: the preview and the turn arrows show the
 * user's physical left and right in every language.
 */
public final class LivenessCameraCard extends Region {

	static final double RADIUS = 16;
	static final double BADGE = 28;
	/** The oval as shares of the card: centre (0.5, 0.489), radii (0.233, 0.406), the 480x360 card's before. */
	static final double OVAL_CY = 176.0 / 360, OVAL_RX = 112.0 / 480, OVAL_RY = 146.0 / 360;

	private final Region frame = new Region();
	private final ImageView preview = new ImageView();
	private final Label placeholder = new Label();
	private final Ellipse oval = new Ellipse();
	private final StackPane badge = new StackPane();
	private final SVGPath badgeIcon = new SVGPath();
	private final Rectangle clip = new Rectangle();
	private Shape scrim;
	private double cardWidth = -1, cardHeight = -1;

	public LivenessCameraCard() {
		setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);
		frame.getStyleClass().add("liveness-preview");
		preview.setPreserveRatio(true);
		preview.setScaleX(-1); // a selfie view; the engine's frames are never mirrored
		placeholder.getStyleClass().add("liveness-preview-placeholder");
		placeholder.setWrapText(true);
		oval.getStyleClass().add("liveness-oval");
		badge.getStyleClass().add("liveness-badge");
		badgeIcon.getStyleClass().add("liveness-badge-icon");
		badgeIcon.setScaleX(0.75); // the 24 px Material paths at 18 px
		badgeIcon.setScaleY(0.75);
		badge.getChildren().add(badgeIcon);
		clip.setArcWidth(2 * RADIUS);
		clip.setArcHeight(2 * RADIUS);
		setClip(clip);
		setMouseTransparent(true);
		getChildren().addAll(frame, preview, placeholder, oval, badge);
	}

	ImageView preview() { return preview; }
	Label placeholder() { return placeholder; }
	Ellipse oval() { return oval; }
	StackPane badge() { return badge; }
	SVGPath badgeIcon() { return badgeIcon; }

	/** The card's size within this region, after layout. */
	double cardWidth() { return cardWidth; }
	double cardHeight() { return cardHeight; }

	@Override
	protected double computeMinWidth(double height) {
		return 160;
	}

	@Override
	protected double computeMinHeight(double width) {
		return 120;
	}

	@Override
	protected void layoutChildren() {
		double w = getWidth(), h = getHeight();
		double ch = Math.min(h, w * 3 / 4), cw = ch * 4 / 3;
		double x = (w - cw) / 2, y = (h - ch) / 2;
		if (cw != cardWidth || ch != cardHeight) {
			cardWidth = cw;
			cardHeight = ch;
			oval.setRadiusX(cw * OVAL_RX);
			oval.setRadiusY(ch * OVAL_RY);
			if (scrim != null) {
				getChildren().remove(scrim);
			}
			scrim = Shape.subtract(new Rectangle(cw, ch),
					new Ellipse(cw / 2, ch * OVAL_CY, oval.getRadiusX(), oval.getRadiusY()));
			scrim.getStyleClass().add("liveness-mask");
			getChildren().add(getChildren().indexOf(oval), scrim);
			preview.setFitWidth(cw);
			preview.setFitHeight(ch);
		}
		clip.setX(x);
		clip.setY(y);
		clip.setWidth(cw);
		clip.setHeight(ch);
		frame.resizeRelocate(x, y, cw, ch);
		layoutInArea(preview, x, y, cw, ch, 0, HPos.CENTER, VPos.CENTER);
		layoutInArea(placeholder, x + 24, y, cw - 48, ch - 16, 0, HPos.CENTER, VPos.BOTTOM);
		scrim.relocate(x, y);
		oval.setCenterX(x + cw / 2);
		oval.setCenterY(y + ch * OVAL_CY);
		double top = Math.max(y, oval.getCenterY() - oval.getRadiusY() - BADGE / 2);
		badge.resizeRelocate(oval.getCenterX() - BADGE / 2, top, BADGE, BADGE);
	}
}
