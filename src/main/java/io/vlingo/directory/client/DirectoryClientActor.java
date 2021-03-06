// Copyright © 2012-2018 Vaughn Vernon. All rights reserved.
//
// This Source Code Form is subject to the terms of the
// Mozilla Public License, v. 2.0. If a copy of the MPL
// was not distributed with this file, You can obtain
// one at https://mozilla.org/MPL/2.0/.

package io.vlingo.directory.client;

import java.nio.ByteBuffer;

import io.vlingo.actors.Actor;
import io.vlingo.actors.Cancellable;
import io.vlingo.actors.Scheduled;
import io.vlingo.actors.Stoppable;
import io.vlingo.directory.client.ServiceRegistrationInfo.Location;
import io.vlingo.directory.model.message.RegisterService;
import io.vlingo.directory.model.message.ServiceRegistered;
import io.vlingo.wire.channel.ChannelReaderConsumer;
import io.vlingo.wire.channel.SocketChannelWriter;
import io.vlingo.wire.message.ByteBufferAllocator;
import io.vlingo.wire.message.PublisherAvailability;
import io.vlingo.wire.message.RawMessage;
import io.vlingo.wire.multicast.Group;
import io.vlingo.wire.multicast.MulticastSubscriber;
import io.vlingo.wire.node.Name;

public class DirectoryClientActor extends Actor implements DirectoryClient, ChannelReaderConsumer, Scheduled, Stoppable {
  private final ByteBuffer buffer;
  private final Cancellable cancellable;
  private PublisherAvailability directory;
  private SocketChannelWriter directoryChannel;
  private final ServiceDiscoveryInterest interest;
  private RawMessage registerService;
  private final MulticastSubscriber subscriber;
  
  public DirectoryClientActor(
          final ServiceDiscoveryInterest interest,
          final Group directoryPublisherGroup,
          final int maxMessageSize,
          final long processingInterval,
          final int processingTimeout)
  throws Exception {
    this.interest = interest;
    this.buffer = ByteBufferAllocator.allocate(maxMessageSize);
    this.subscriber = new MulticastSubscriber(ClientName, directoryPublisherGroup, maxMessageSize, processingTimeout, logger());
    this.subscriber.openFor(selfAs(ChannelReaderConsumer.class));
    this.cancellable = stage().scheduler().schedule(selfAs(Scheduled.class), null, 0, processingInterval);
  }

  //====================================
  // DirectoryClient
  //====================================

  @Override
  public void register(final ServiceRegistrationInfo info) {
    final RegisterService converted = RegisterService.as(Name.of(info.name), Location.toAddresses(info.locations));
    this.registerService = RawMessage.from(0, 0, converted.toString());
  }

  //====================================
  // ChannelReaderConsumer
  //====================================

  @Override
  public void consume(final RawMessage message) {
    final String incoming = message.asTextMessage();
    final ServiceRegistered serviceRegistered = ServiceRegistered.from(incoming);
    if (serviceRegistered.isValid() && interest.interestedIn(serviceRegistered.name.value())) {
      interest.inform(new ServiceRegistrationInfo(serviceRegistered.name.value(), Location.from(serviceRegistered.addresses)));
    } else {
      manageDirectoryChannel(incoming);
    }
  }

  //====================================
  // Scheduled
  //====================================

  @Override
  public void intervalSignal(final Scheduled scheduled, final Object data) {
    subscriber.probeChannel();
    
    registerService();
  }

  //====================================
  // Stoppable
  //====================================

  @Override
  public void stop() {
    this.cancellable.cancel();
    
    super.stop();
  }

  //====================================
  // internal implementation
  //====================================

  private void manageDirectoryChannel(final String maybePublisherAvailability) {
    final PublisherAvailability publisherAvailability = PublisherAvailability.from(maybePublisherAvailability);
    if (publisherAvailability.isValid()) {
      if (!publisherAvailability.equals(directory)) {
        directory = publisherAvailability;
        if (directoryChannel != null) {
          directoryChannel.close();
        }
        directoryChannel = new SocketChannelWriter(directory.toAddress(), logger());
      }
    }
  }

  private void registerService() {
    if (directoryChannel != null && registerService != null) {
      final int expected = registerService.totalLength();
      final int actual = directoryChannel.write(registerService, buffer);
      if (actual != expected) {
        logger().log("DIRECTORY CLIENT: Did not send full service registration message: " + registerService.asTextMessage());
      }
    }
  }
}
