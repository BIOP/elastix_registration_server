/*-
 * #%L
 * BIOP Elastix Registration Server
 * %%
 * Copyright (C) 2021 Nicolas Chiaruttini, EPFL
 * %%
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * 3. Neither the name of the EPFL, ECOLE POLYTECHNIQUE FEDERALE DE LAUSANNE, Switzerland, BioImaging And Optics Platform (BIOP), 2021 nor the names of its contributors
 *    may be used to endorse or promote products derived from this software without
 *    specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */
package ch.epfl.biop.server;

/**
 * Parameters for the registration server configuration
 *
 * This object is created by deserializing a json file when given as an argument when starting the server
 *
 * The values in this class are default values which can are useless to start the server
 * from within an IDE. Of course the default values need to be modified to your local configuration.
 *
 */
public class RegistrationServerConfig {

    /**
     * Location of the elastix executable file on the server
     */
    public String elaxtixLocation = "C:\\elastix-5.0.1-win64\\elastix.exe";

    /**
     * Location of the transformix executable file on the server
     */
    public String transformixLocation = "C:\\elastix-5.0.1-win64\\transformix.exe";

    /**
     * Local port of the registration server
     */
    public int localPort = 8090;

    /**
     * Http request timeout
     */
    public int requestTimeOutInMs = 80000;

    /**
     * Http request timeout
     */
    public int nThreadsPerElastixTask = 4;

    /**
     * Estimated duration for processing a single elastix registration task
     * No difference is made between different types of registration
     * This is used to estimate the time required to process a queue of tasks
     *
     * Time to process the whole queue =
     *      elastixTaskEstimatedDurationInMs * number of tasks in the queue
     *
     *
     * Being able to process many tasks in parallel (provided that the server is not at 100%)
     * reduces the task estimated duration because they can be processed in parallel
     *
     */
    public int elastixTaskEstimatedDurationInMs = 5000;

    /**
     * Estimated maximum time allowed for the queue.
     *
     * If elastixTaskEstimatedDurationInMs/1000 * number of tasks in the queue is strictly superior to maxQueueEstimatedWaitingTimeInS
     *
     * Then requests are ignored with a 503 Service Unavailable error message
     */
    public int maxQueueEstimatedWaitingTimeInS = 120;

    /**
     * Each client - if its registration job is not immediately processed, has its job put into a queue.
     * The client should check regularly the state of the queue. When its job is at the front
     * of the queue, he sends its real registration job request - send the images and the
     * registration parameters.
     *
     * When a job is queued, the server estimates and send back to the client the duration taken to process
     * all tasks in the queue located before the client task.
     *
     * For instance, if the registration task of a client is number 20, and each task takes 5 seconds,
     * the client does in theory not need to check the queue's state before (20*5 s = 100 s).
     * However, it may happen that the queue takes a much shorter time to be processed :
     * jobs may be shorter to process, or they can be all cancelled unexpectedly or they can
     * generate errors because the request is wrong for some reason.
     *
     * In this case, the client needs to wake up before 100 s. And this is what this parameter sets :
     * the maximal amount of time that the client will spend before taking some news of the queue.
     *
     * In this case - taking the example before, if all the 19 tasks of the queue are cancelled, the client
     * will wait at maximum maxDelayBetweenQueueUpdateRequestInS before being being informed that the queue
     * is empty. The default value, 10s - will avoid 90s of server idle time.
     */
    public int maxDelayBetweenQueueUpdateRequestInS = 5;

    /**
     * Maximum number of simultaneous requests being processed by the server.
     *
     * It's probably a good idea to scale this value with the number of core of the server
     *
     */
    public int maxNumberOfSimultaneousRequests = 4;

    /**
     * Directory used to store temporarily each jobs data.
     *
     * Writing to the HDD is necessary because how this server works is ba launching
     * elastix and transformix jobs by passing parameters as files.
     *
     * If storeJobsData is true and if a request contains metadata, then the job
     * input and output data is kept on the server in this folder. Currently, the client
     * works such as metadata is only sent when the user agrees that data is stored in the server.
     *
     * Thus, automatically, each jobs data is cleaned from the server after it's been processed
     * when the user does not consent to share its data - because no metadata will be contained
     * in the request.
     *
     */
    public String jobsDataLocation = "src/test/resources/tmp/";

    /**
     * Each elastix job has a unique index. It would make sense to always start at 0 and increment
     * but the server may have crashed. If there were some jobs kept on the server, you do
     * not want to erase these previous, thus you will restart the server with a job index greater
     * that the one of the last saved job data.
     */
    public int initialElastixJobIndex = 0;

    /**
     * See explanation above - but actually no transformix job is kept of the server - so this parameter
     * is useless
     */
    public int initialTransformixIndex = 0;

    /**
     * If set to false, no task will let a trace on the server (except burning indirectly CO2 to generate electricity)
     */
    public boolean storeJobsData = true;

    /**
     * In bytes, the maximal size of a file which will be accepted ba the server
     * Default value 1 Mo - this is a security in order to avoid heavy traffic on the server
     * this obviously also limits the kind of registration that this server can process
     */
    public long maxFileSize = 1024 * 1024;

}
