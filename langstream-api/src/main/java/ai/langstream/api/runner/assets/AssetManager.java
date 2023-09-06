/*
 * Copyright DataStax, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.langstream.api.runner.assets;

import ai.langstream.api.model.AssetDefinition;

/** Body of the agent */
public interface AssetManager {

    boolean assetExists(AssetDefinition assetDefinition) throws Exception;

    void deployAsset(AssetDefinition assetDefinition) throws Exception;

    default void close() throws Exception {}
}
