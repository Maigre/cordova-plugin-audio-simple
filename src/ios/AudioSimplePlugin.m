//
//  AudioSimplePlugin.m
//  cordova-plugin-audio-simple — iOS bridge entry point.
//
//  Mirrors the Android AudioSimplePlugin shape: per-player NSMutableDictionary
//  keyed by NSInteger handle + a long-lived events callback that fans out
//  player events to JS. Adds iOS-specific actions migrated from
//  cordova-plugin-audiofocus Round 22 (setupNowPlaying, setResumeSnapshot,
//  activateSession, releaseSession, resetSession).
//

#import <Cordova/CDV.h>
#import "AudioSimplePlayer.h"
#import "AudioSimpleSession.h"

@interface AudioSimplePlugin : CDVPlugin <AudioSimplePlayerDelegate>
@property (nonatomic, strong) NSMutableDictionary<NSNumber*, AudioSimplePlayer*>* players;
@property (nonatomic, copy)   NSString* eventsCallbackId;
@property (nonatomic, assign) NSInteger nextHandle;
@end

@implementation AudioSimplePlugin

- (void) pluginInitialize {
    self.players = [NSMutableDictionary dictionary];
    self.nextHandle = 1;
}

#pragma mark - per-player actions

- (void) create:(CDVInvokedUrlCommand*)command {
    NSDictionary* opts = (command.arguments.count > 0 && [command.arguments[0] isKindOfClass:[NSDictionary class]])
        ? command.arguments[0] : @{};
    NSString* src = opts[@"src"];
    BOOL loop = [opts[@"loop"] boolValue];
    double volume = [opts[@"volume"] doubleValue];
    if (volume <= 0 && ![opts[@"volume"] isKindOfClass:[NSNumber class]]) volume = 1.0;

    NSInteger handle = self.nextHandle++;
    AudioSimplePlayer* p = [[AudioSimplePlayer alloc] initWithHandle:handle
                                                                 src:src
                                                                loop:loop
                                                              volume:(float)volume];
    p.delegate = self;
    self.players[@(handle)] = p;

    CDVPluginResult* result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsInt:(int)handle];
    [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
}

- (AudioSimplePlayer*) playerForCommand:(CDVInvokedUrlCommand*)command {
    if (command.arguments.count < 1) return nil;
    NSNumber* h = command.arguments[0];
    if (![h isKindOfClass:[NSNumber class]]) return nil;
    return self.players[h];
}

- (void) load:(CDVInvokedUrlCommand*)command {
    AudioSimplePlayer* p = [self playerForCommand:command];
    if (!p) { [self failNoHandle:command]; return; }
    NSString* src = (command.arguments.count > 1) ? command.arguments[1] : nil;
    // The Android side accepts a fresh src on load; iOS we use the src given
    // at create() time (set on the player). If the JS layer passes a different
    // src here, ignore — re-loading a different file means new Player handle.
    (void)src;
    [p load];
    [self ok:command];
}

- (void) prewarm:(CDVInvokedUrlCommand*)command {
    AudioSimplePlayer* p = [self playerForCommand:command];
    if (!p) { [self failNoHandle:command]; return; }
    [p prewarm];
    [self ok:command];
}

- (void) play:(CDVInvokedUrlCommand*)command {
    AudioSimplePlayer* p = [self playerForCommand:command];
    if (!p) { [self failNoHandle:command]; return; }
    [p play];
    [self ok:command];
}

- (void) pause:(CDVInvokedUrlCommand*)command {
    AudioSimplePlayer* p = [self playerForCommand:command];
    if (!p) { [self failNoHandle:command]; return; }
    [p pause];
    [self ok:command];
}

- (void) stop:(CDVInvokedUrlCommand*)command {
    AudioSimplePlayer* p = [self playerForCommand:command];
    if (!p) { [self failNoHandle:command]; return; }
    [p stop];
    [self ok:command];
}

- (void) seek:(CDVInvokedUrlCommand*)command {
    AudioSimplePlayer* p = [self playerForCommand:command];
    if (!p) { [self failNoHandle:command]; return; }
    if (command.arguments.count < 2) { [self failBadArgs:command]; return; }
    double seconds = [command.arguments[1] doubleValue];
    [p seekTo:seconds];
    [self ok:command];
}

- (void) getPosition:(CDVInvokedUrlCommand*)command {
    AudioSimplePlayer* p = [self playerForCommand:command];
    if (!p) { [self failNoHandle:command]; return; }
    NSTimeInterval pos = [p currentPosition];
    CDVPluginResult* result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDouble:pos];
    [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
}

- (void) setVolume:(CDVInvokedUrlCommand*)command {
    AudioSimplePlayer* p = [self playerForCommand:command];
    if (!p) { [self failNoHandle:command]; return; }
    if (command.arguments.count < 2) { [self failBadArgs:command]; return; }
    double v = [command.arguments[1] doubleValue];
    [p setVolume:(float)v];
    [self ok:command];
}

- (void) fade:(CDVInvokedUrlCommand*)command {
    AudioSimplePlayer* p = [self playerForCommand:command];
    if (!p) { [self failNoHandle:command]; return; }
    if (command.arguments.count < 4) { [self failBadArgs:command]; return; }
    double from = [command.arguments[1] doubleValue];
    double to   = [command.arguments[2] doubleValue];
    NSInteger ms = [command.arguments[3] integerValue];
    [p fadeFrom:(float)from to:(float)to durationMs:ms];
    [self ok:command];
}

- (void) setLoop:(CDVInvokedUrlCommand*)command {
    AudioSimplePlayer* p = [self playerForCommand:command];
    if (!p) { [self failNoHandle:command]; return; }
    if (command.arguments.count < 2) { [self failBadArgs:command]; return; }
    BOOL loop = [command.arguments[1] boolValue];
    [p setLoopFlag:loop];
    [self ok:command];
}

- (void) unload:(CDVInvokedUrlCommand*)command {
    AudioSimplePlayer* p = [self playerForCommand:command];
    if (!p) { [self ok:command]; return; }  // already gone — not an error
    [p unload];
    [self.players removeObjectForKey:@(p.handle)];
    [self ok:command];
}

#pragma mark - plugin-level actions

- (void) ping:(CDVInvokedUrlCommand*)command {
    NSDictionary* info = @{
        @"plugin":   @"cordova-plugin-audio-simple",
        @"version":  @"0.3.3",
        @"platform": @"ios",
        @"engine":   @"AVAudioPlayer",
    };
    CDVPluginResult* result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:info];
    [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
}

// Long-lived event channel. JS shim calls this once on first Player()
// construction. emitToJS uses keepCallback=YES so subsequent events keep
// flowing through the same callback id.
- (void) subscribeEvents:(CDVInvokedUrlCommand*)command {
    self.eventsCallbackId = command.callbackId;
    CDVPluginResult* result = [CDVPluginResult resultWithStatus:CDVCommandStatus_NO_RESULT];
    result.keepCallback = @YES;
    [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
}

- (void) releaseAll:(CDVInvokedUrlCommand*)command {
    for (NSNumber* h in [self.players allKeys]) {
        AudioSimplePlayer* p = self.players[h];
        [p unload];
    }
    [self.players removeAllObjects];
    [self ok:command];
}

// Android-only action mirror — no FG service to start on iOS.
- (void) startService:(CDVInvokedUrlCommand*)command {
    [self ok:command];
}

#pragma mark - session lifecycle (migrated from audiofocus Round 22)

- (void) activateSession:(CDVInvokedUrlCommand*)command {
    NSError* err = nil;
    BOOL ok = [[AudioSimpleSession sharedSession] activate:&err];
    if (ok) [self ok:command];
    else    [self failWithError:command error:err];
}

- (void) deactivateSession:(CDVInvokedUrlCommand*)command {
    NSError* err = nil;
    BOOL ok = [[AudioSimpleSession sharedSession] deactivateNotifyOthers:YES error:&err];
    if (ok) [self ok:command];
    else    [self failWithError:command error:err];
}

- (void) releaseSession:(CDVInvokedUrlCommand*)command {
    NSError* err = nil;
    BOOL ok = [[AudioSimpleSession sharedSession] deactivateNotifyOthers:YES error:&err];
    if (ok) [self ok:command];
    else    [self failWithError:command error:err];
}

- (void) resetSession:(CDVInvokedUrlCommand*)command {
    [self.commandDelegate runInBackground:^{
        NSError* err = nil;
        BOOL ok = [[AudioSimpleSession sharedSession] reset:&err];
        if (ok) [self ok:command];
        else    [self failWithError:command error:err];
    }];
}

- (void) getSessionState:(CDVInvokedUrlCommand*)command {
    NSDictionary* state = [[AudioSimpleSession sharedSession] snapshotState];
    CDVPluginResult* result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:state];
    [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
}

#pragma mark - Now Playing (migrated from audiofocus Round 22)

- (void) setupNowPlaying:(CDVInvokedUrlCommand*)command {
    NSDictionary* opts = (command.arguments.count > 0 && [command.arguments[0] isKindOfClass:[NSDictionary class]])
        ? command.arguments[0] : @{};
    [[AudioSimpleSession sharedSession] setupNowPlaying:opts];
    [self ok:command];
}

- (void) clearNowPlaying:(CDVInvokedUrlCommand*)command {
    [[AudioSimpleSession sharedSession] clearNowPlaying];
    [self ok:command];
}

#pragma mark - step-state cache (migrated from audiofocus Round 21)

- (void) setResumeSnapshot:(CDVInvokedUrlCommand*)command {
    NSDictionary* snap = (command.arguments.count > 0 && [command.arguments[0] isKindOfClass:[NSDictionary class]])
        ? command.arguments[0] : @{};
    [[AudioSimpleSession sharedSession] setResumeSnapshot:snap];
    [self ok:command];
}

- (void) getResumeSnapshot:(CDVInvokedUrlCommand*)command {
    NSDictionary* snap = [[AudioSimpleSession sharedSession] getResumeSnapshot];
    CDVPluginResult* result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:snap];
    [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
}

- (void) clearResumeSnapshot:(CDVInvokedUrlCommand*)command {
    [[AudioSimpleSession sharedSession] clearResumeSnapshot];
    [self ok:command];
}

#pragma mark - AudioSimplePlayerDelegate

- (void) player:(AudioSimplePlayer*)player emitEvent:(NSString*)event payload:(NSDictionary*)payload {
    if (self.eventsCallbackId == nil) return;

    NSMutableDictionary* message = [NSMutableDictionary dictionary];
    message[@"id"]    = @(player.handle);
    message[@"event"] = event;
    if (payload[@"error"]) message[@"error"] = payload[@"error"];

    CDVPluginResult* result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:message];
    result.keepCallback = @YES;
    [self.commandDelegate sendPluginResult:result callbackId:self.eventsCallbackId];
}

#pragma mark - helpers

- (void) ok:(CDVInvokedUrlCommand*)command {
    CDVPluginResult* result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
    [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
}

- (void) failBadArgs:(CDVInvokedUrlCommand*)command {
    CDVPluginResult* result = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"bad_args"];
    [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
}

- (void) failNoHandle:(CDVInvokedUrlCommand*)command {
    CDVPluginResult* result = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"no_such_handle"];
    [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
}

- (void) failWithError:(CDVInvokedUrlCommand*)command error:(NSError*)error {
    NSString* msg = error.localizedDescription ?: @"unknown_error";
    CDVPluginResult* result = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:msg];
    [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
}

@end
