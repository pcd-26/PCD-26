/**
 * Platform-thread execution strategy for Poool.
 *
 * <p>This package contains the first concurrent runtime for the game. The
 * runtime keeps the sequential domain model as the reference semantics and
 * changes only the execution policy: input producers and the bot submit
 * commands asynchronously, while one controller platform thread serializes all
 * game-rule mutations on the owned game model and publishes immutable
 * snapshots for readers. The owned game model uses a worker-based threaded
 * physics engine for the expensive independent phases of each simulation tick.
 */
package pcd.poool.threaded;
