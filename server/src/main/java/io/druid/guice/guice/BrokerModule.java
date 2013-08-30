/*
 * Druid - a distributed column store.
 * Copyright (C) 2012, 2013  Metamarkets Group Inc.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package io.druid.guice.guice;

import com.google.inject.Binder;
import com.google.inject.Module;
import io.druid.client.BrokerServerView;
import io.druid.client.CachingClusteredClient;
import io.druid.client.TimelineServerView;
import io.druid.client.cache.Cache;
import io.druid.client.cache.CacheProvider;
import io.druid.query.MapQueryToolChestWarehouse;
import io.druid.query.QueryToolChestWarehouse;

/**
 */
public class BrokerModule implements Module
{
  @Override
  public void configure(Binder binder)
  {
    binder.bind(QueryToolChestWarehouse.class).to(MapQueryToolChestWarehouse.class);

    binder.bind(CachingClusteredClient.class).in(LazySingleton.class);
    binder.bind(TimelineServerView.class).to(BrokerServerView.class).in(LazySingleton.class);

    binder.bind(Cache.class).toProvider(CacheProvider.class).in(ManageLifecycle.class);
    JsonConfigProvider.bind(binder, "druid.broker.cache", CacheProvider.class);
  }
}