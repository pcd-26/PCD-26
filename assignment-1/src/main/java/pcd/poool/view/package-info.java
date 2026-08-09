/**
 * Swing rendering layer for Poool.
 *
 * <p>The package keeps the visible game state separate from the authoritative
 * physics model. The runtime copies data into {@link pcd.poool.view.board.ViewModel},
 * the EDT paints that copied state through {@link pcd.poool.view.board.ViewFrame},
 * and the launchers interact with both through {@link pcd.poool.view.board.View}.
 *
 * <p>Input is translated into callbacks instead of direct model access, which
 * makes the rendering layer easier to explain, test, and swap out.
 */
package pcd.poool.view;
