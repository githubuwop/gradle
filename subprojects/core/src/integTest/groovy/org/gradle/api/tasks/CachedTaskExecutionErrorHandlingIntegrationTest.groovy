/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.tasks

import org.gradle.api.logging.configuration.ShowStacktrace
import org.gradle.caching.internal.controller.DefaultBuildCacheController
import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.integtests.fixtures.DirectoryBuildCacheFixture
import spock.lang.Unroll

class CachedTaskExecutionErrorHandlingIntegrationTest extends AbstractIntegrationSpec implements DirectoryBuildCacheFixture {

    def setup() {
        settingsFile << """
            class FailingBuildCache extends AbstractBuildCache {
                boolean shouldFail
                boolean recoverable = true
            }
            
            class FailingBuildCacheServiceFactory implements BuildCacheServiceFactory<FailingBuildCache> {
                FailingBuildCacheService createBuildCacheService(FailingBuildCache configuration, Describer describer) {
                    return new FailingBuildCacheService(configuration.shouldFail, configuration.recoverable)
                }
            }
            
            class FailingBuildCacheService implements BuildCacheService {
                boolean shouldFail
                boolean recoverable
                
                FailingBuildCacheService(boolean shouldFail, boolean recoverable) {
                    this.shouldFail = shouldFail
                    this.recoverable = recoverable
                }
                
                @Override
                boolean load(BuildCacheKey key, BuildCacheEntryReader reader) throws BuildCacheException {
                    if (shouldFail) {
                        throw new BuildCacheException("Unable to read " + key)
                    } else {
                        return false
                    }
                }
    
                @Override
                void store(BuildCacheKey key, BuildCacheEntryWriter writer) throws BuildCacheException {
                    if (shouldFail) {
                        if (recoverable) {
                            throw new BuildCacheException("Unable to write " + key)
                        }
                        throw new RuntimeException("Failure while packing")
                    }
                }
    
                @Override
                void close() throws IOException {
                }
            }
            
            buildCache {
                registerBuildCacheService(FailingBuildCache, FailingBuildCacheServiceFactory)
                
                local {
                    enabled = false
                }
                
                remote(FailingBuildCache) {
                    shouldFail = gradle.startParameter.systemPropertiesArgs.containsKey("fail")
                    recoverable = gradle.startParameter.systemPropertiesArgs.containsKey("recoverable")
                    push = true
                }
            }
        """

        buildFile << """
            apply plugin: "base"
            
            @CacheableTask
            class CustomTask extends DefaultTask {
                @OutputFile 
                File outputFile = new File(temporaryDir, "output.txt")
                
                @TaskAction
                void generate() {
                    outputFile.text = "done"
                }
            }
            
            task customTask(type: CustomTask)
            task anotherCustomTask(type: CustomTask)
            
            // All of our tests just run 'assemble' and we need several 
            // tasks that are cacheable.

            // CustomTask is a dummy cacheable task that will cause
            // enough requests to the build cache to trip our short circuiting if
            // there are errors.
            assemble.dependsOn customTask, anotherCustomTask
        """

        executer.withBuildCacheEnabled()
    }

    @Unroll
    def "cache switches off after third recoverable error for the current build (stacktraces: #showStractrace)"() {
        // Need to do it like this because stacktraces are always enabled for integration tests
        settingsFile << """
            gradle.startParameter.setShowStacktrace(org.gradle.api.logging.configuration.ShowStacktrace.$showStractrace)
        """
        if (expectStacktrace) {
            executer.withStackTraceChecksDisabled()
        }

        when:
        succeeds "assemble", "-Dfail", "-Drecoverable"
        then:
        output.count("Could not load entry") == 2
        output.count("Could not store entry") == 1
        output.count("The remote build cache is now disabled because 3 recoverable errors were encountered.") == 1
        output.count("The remote build cache was disabled during the build because 3 recoverable errors were encountered.") == 1
        if (expectStacktrace) {
            assert stackTraceContains(DefaultBuildCacheController)
        }

        when:
        succeeds "clean", "assemble"

        then: "build cache is still enabled during next build"
        !output.contains("The remote build cache is now disabled")
        !output.contains("The remote build cache was disabled during the build")
        if (expectStacktrace) {
            assert !stackTraceContains(DefaultBuildCacheController)
        }
        skippedTasks.empty

        where:
        showStractrace                     | expectStacktrace
        ShowStacktrace.INTERNAL_EXCEPTIONS | false
        ShowStacktrace.ALWAYS              | true
        ShowStacktrace.ALWAYS_FULL         | true
    }

    @Unroll
    def "cache switches off after first non-recoverable error for the current build (stacktraces: #showStractrace)"() {
        // Need to do it like this because stacktraces are always enabled for integration tests
        settingsFile << """
            gradle.startParameter.setShowStacktrace(org.gradle.api.logging.configuration.ShowStacktrace.$showStractrace)
        """
        if (expectStacktrace) {
            executer.withStackTraceChecksDisabled()
        }

        when:
        succeeds "assemble", "-Dfail"
        then:
        output.count("Could not load entry") == 1
        output.count("Could not store entry") == 1
        output.count("Failure while packing") == 1
        output.count("The remote build cache is now disabled because a non-recoverable error was encountered.") == 1
        output.count("The remote build cache was disabled during the build because a non-recoverable error was encountered.") == 1
        if (expectStacktrace) {
            assert stackTraceContains(DefaultBuildCacheController)
        }

        when:
        succeeds "clean", "assemble"

        then: "build cache is still enabled during next build"
        !output.contains("The remote build cache is now disabled")
        !output.contains("The remote build cache was disabled during the build")
        if (expectStacktrace) {
            assert !stackTraceContains(DefaultBuildCacheController)
        }
        skippedTasks.empty

        where:
        showStractrace                     | expectStacktrace
        ShowStacktrace.INTERNAL_EXCEPTIONS | false
        ShowStacktrace.ALWAYS              | true
        ShowStacktrace.ALWAYS_FULL         | true
    }

    boolean stackTraceContains(Class<?> type) {
        output.contains("\tat ${type.name}.")
    }
}
