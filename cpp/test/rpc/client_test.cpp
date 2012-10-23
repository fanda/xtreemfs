/*
 * Copyright (c) 2012 by Michael Berlin, Zuse Institute Berlin
 *
 * Licensed under the BSD License, see LICENSE file for details.
 *
 */

#include <gtest/gtest.h>

#include <boost/scoped_ptr.hpp>

#include "common/test_environment.h"
#include "common/test_rpc_server_dir.h"
#include "common/test_rpc_server_mrc.h"
#include "common/test_rpc_server_osd.h"
#include "libxtreemfs/client.h"
#include "libxtreemfs/client_implementation.h"
#include "libxtreemfs/options.h"
#include "libxtreemfs/uuid_resolver.h"
#include "libxtreemfs/volume.h"
#include "libxtreemfs/xtreemfs_exception.h"
#include "rpc/client.h"

using namespace std;
using namespace xtreemfs::pbrpc;
using namespace xtreemfs::util;

namespace xtreemfs {
namespace rpc {

class ClientTest : public ::testing::Test {
 protected:
  virtual void SetUp() {
    initialize_logger(LEVEL_DEBUG);

    ASSERT_TRUE(test_env.Start());
  }

  virtual void TearDown() {
    test_env.Stop();
  }

  TestEnvironment test_env;
};

class ClientTestFastTimeout : public ClientTest {
 protected:
  virtual void SetUp() {
    test_env.options.max_tries = 1;
    test_env.options.connect_timeout_s = 1;
    test_env.options.request_timeout_s = 1;

    ClientTest::SetUp();
  }
};

class ClientTestFastLingerTimeoutConnectTimeout : public ClientTest {
 protected:
  virtual void SetUp() {
    test_env.options.linger_timeout_s = 1;
    test_env.options.request_timeout_s = 1;
    test_env.options.max_tries = 1;

    // We set an address which definitely won't work.
    test_env.options.service_address = "130.73.78.254:80";

    ClientTest::SetUp();
  }
};

class ClientTestFastLingerTimeout : public ClientTest {
 protected:
  virtual void SetUp() {
    test_env.options.linger_timeout_s = 1;
    test_env.options.request_timeout_s = 1;

    ClientTest::SetUp();
  }
};

class ClientTestDropConnection : public ClientTest {
 protected:
  virtual void SetUp() {
    test_env.options.max_tries = 1;
    test_env.options.connect_timeout_s = 1;
    test_env.options.request_timeout_s = 1;

    test_env.dir->DropConnection();

    ClientTest::SetUp();
  }
};

/** Is a timed out request successfully aborted? */
TEST_F(ClientTestFastTimeout, TimeoutHandling) {
  test_env.dir->AddDropRule(new DropNRule(1));

  EXPECT_NO_THROW({
    string exception_text;
    try {
      string unused_string;
      test_env.client->GetUUIDResolver()->
          VolumeNameToMRCUUID("test", &unused_string);
    } catch (const IOException& exception) {
      exception_text = exception.what();
    }
    EXPECT_TRUE(
        exception_text.find("Request timed out") != string::npos);
  });
}

/** A request fails if the connection attempt was dropped. */
TEST_F(ClientTestDropConnection, ConnectionTimeout) {
  EXPECT_NO_THROW({
    string exception_text;
    try {
      string unused_string;
      test_env.client->GetUUIDResolver()->
          VolumeNameToMRCUUID("test", &unused_string);
    } catch (const IOException& exception) {
      exception_text = exception.what();
    }
    EXPECT_TRUE(
        exception_text.find("Request timed out") != string::npos);
  });
}

/** Inactive connections shall be successfully closed. */
TEST_F(ClientTestFastLingerTimeout, LingerTests) {
  string unused_string;
  test_env.client->GetUUIDResolver()->
      VolumeNameToMRCUUID("test", &unused_string);

  boost::this_thread::sleep(boost::posix_time::seconds(2));

  EXPECT_EQ(0,
            dynamic_cast<xtreemfs::ClientImplementation*>(
                test_env.client.get())->network_client_->connections_.size());
}

/** Connect timeout callbacks executed after deleting
 *  a xtreemfs::rpc::ClientConnection object due to an expired
 *  linger timeout do not result in a segmentation fault. */
TEST_F(ClientTestFastLingerTimeoutConnectTimeout, LingerTests) {
  EXPECT_THROW({
    string unused_string;
    test_env.client->GetUUIDResolver()->
        VolumeNameToMRCUUID("test", &unused_string);
  }, IOException);

  // Do not race with handleTimeout() which is removing
  // the client connection due to the expired linger timeout.
  boost::this_thread::sleep(boost::posix_time::millisec(50));

  // At this point the connection must have been deleted
  // due to the very low linger timeout.
  EXPECT_EQ(0,
            dynamic_cast<xtreemfs::ClientImplementation*>(
                test_env.client.get())->network_client_->connections_.size());
}

}  // namespace rpc
}  // namespace xtreemfs