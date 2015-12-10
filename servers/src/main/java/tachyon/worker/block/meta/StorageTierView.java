/*
 * Licensed to the University of California, Berkeley under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package tachyon.worker.block.meta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.google.common.base.Preconditions;

import tachyon.worker.block.BlockMetadataManagerView;

/**
 * This class is a wrapper of {@link StorageTier} to provide more limited access
 */
public final class StorageTierView {

  /** the {@link StorageTier} this view is derived from */
  private final StorageTier mTier;
  /** a list of {@link StorageDirView} under this StorageTierView */
  private List<StorageDirView> mDirViews = new ArrayList<StorageDirView>();
  /** the {@link BlockMetadataManagerView} this {@link StorageTierView} is under */
  private final BlockMetadataManagerView mManagerView;

  /**
   * Creates a {@link StorageTierView} using the actual {@link StorageTier} and the above
   * {@link BlockMetadataManagerView}
   *
   * @param tier which the tierView is constructed from
   * @param view the {@link BlockMetadataManagerView} this tierView is associated with
   */
  public StorageTierView(StorageTier tier, BlockMetadataManagerView view) {
    mTier = Preconditions.checkNotNull(tier);
    mManagerView = Preconditions.checkNotNull(view);

    for (StorageDir dir : mTier.getStorageDirs()) {
      StorageDirView dirView = new StorageDirView(dir, this, view);
      mDirViews.add(dirView);
    }
  }

  /**
   * @return a list of directory views in this storage tier view
   */
  public List<StorageDirView> getDirViews() {
    return Collections.unmodifiableList(mDirViews);
  }

  /**
   * Returns a directory view for the given index.
   *
   * @param dirIndex the directory view index
   * @return a directory view
   */
  public StorageDirView getDirView(int dirIndex) {
    return mDirViews.get(dirIndex);
  }

  /**
   * @return the storage tier view alias
   */
  public String getTierViewAlias() {
    return mTier.getTierAlias();
  }

  /**
   * @return the ordinal value of the storage tier view
   */
  public int getTierViewOrdinal() {
    return mTier.getTierOrdinal();
  }

  /**
   * @return the block metadata manager view for this storage tier view
   */
  public BlockMetadataManagerView getBlockMetadataManagerView() {
    return mManagerView;
  }
}
