/**
 * Copyright 2019 Anthony Trinh
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ch.qos.logback.core.spi;

import ch.qos.logback.core.Appender;
import org.junit.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * This test shows the general problem I described in LBCORE-67.
 *
 * In the two test cases below, an appender that throws an OutOfMemoryError
 * while getName is called - but this is just an example to show the general
 * problem.
 *
 * The tests below fail without fixing LBCORE-67 and pass when Joern Huxhorn's
 * patch is applied.
 *
 * Additionally, the following, probably more realistic, situations could
 * happen:
 *
 * -addAppender: appenderList.add() could throw OutOfMemoryError. This could
 * only be shown by using an appenderList mock but appenderList does not (and
 * should not) have a setter. This would leave the write lock locked.
 *
 * -iteratorForAppenders: new ArrayList() could throw an OutOfMemoryError,
 * leaving the read lock locked.
 *
 * I can't imagine a bad situation in isAttached, detachAppender(Appender) or
 * detachAppender(String) but I'd change the code anyway for consistency. I'm
 * also pretty sure that something stupid can happen at any time so it's best to
 * just stick to conventions.
 *
 * @author Joern Huxhorn
 */
public class AppenderAttachableImplLockTest {

  private AppenderAttachableImpl<Integer> aai = new AppenderAttachableImpl<Integer>();

  @SuppressWarnings("unchecked")
  @Test(timeout = 5000)
  public void getAppenderBoom() {
    Appender<Integer> mockAppender1 = mock(Appender.class);

    when(mockAppender1.getName()).thenThrow(new OutOfMemoryError("oops"));
    aai.addAppender(mockAppender1);
    try {
      // appender.getName called as a result of next statement
      aai.getAppender("foo");
    } catch (OutOfMemoryError e) {
      // this leaves the read lock locked.
    }

    Appender<Integer> mockAppender2=mock(Appender.class);
    // the next call used to freeze with the earlier ReadWriteLock lock
    // implementation
    aai.addAppender(mockAppender2);
  }

  @SuppressWarnings("unchecked")
  @Test(timeout = 5000)
  public void detachAppenderBoom() throws InterruptedException {
    Appender<Integer> mockAppender = mock(Appender.class);
    when(mockAppender.getName()).thenThrow(new OutOfMemoryError("oops"));
    mockAppender.doAppend(17);

    aai.addAppender(mockAppender);
    Thread t = new Thread(new Runnable() {

      public void run() {
        try {
          // appender.getName called as a result of next statement
          aai.detachAppender("foo");
        } catch (OutOfMemoryError e) {
          // this leaves the write lock locked.
        }
      }
    });
    t.start();
    t.join();

    // the next call used to freeze with the earlier ReadWriteLock lock
    // implementation
    aai.appendLoopOnAppenders(17);
  }
}
