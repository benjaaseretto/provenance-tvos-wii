//
// standalone_runner.m
// Runner nativo autónomo para juegos individuales de Wii en Apple TV (tvOS).
// Elimina la UI de emulador/biblioteca y arranca directamente el juego en pantalla completa.
//

#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>
#import <objc/runtime.h>

#pragma mark - 1. Neutralizador de CloudKit (Evita crash en Apple ID gratis)

@interface CKBlackHole : NSObject
@end

static id blackhole_nil(id self, SEL _cmd, ...) { return nil; }

@implementation CKBlackHole
+ (BOOL)resolveInstanceMethod:(SEL)sel {
    class_addMethod(self, sel, (IMP)blackhole_nil, "@@:");
    return YES;
}
+ (BOOL)resolveClassMethod:(SEL)sel {
    class_addMethod(object_getClass(self), sel, (IMP)blackhole_nil, "@@:");
    return YES;
}
- (BOOL)isKindOfClass:(Class)cls { return YES; }
- (NSString *)containerIdentifier { return @"iCloud.standalone.game"; }
@end

static id ck_container(id self, SEL _cmd, ...) {
    static CKBlackHole *hole;
    static dispatch_once_t once;
    dispatch_once(&once, ^{ hole = [CKBlackHole new]; });
    return hole;
}

static void desactivar_cloudkit(void) {
    Class ck = objc_getClass("CKContainer");
    if (!ck) {
        [[NSBundle bundleWithPath:@"/System/Library/Frameworks/CloudKit.framework"] load];
        ck = objc_getClass("CKContainer");
    }
    if (!ck) return;

    Class meta = object_getClass(ck);
    SEL classSels[] = { @selector(containerWithIdentifier:), @selector(defaultContainer) };
    for (int i = 0; i < 2; i++) {
        Method m = class_getClassMethod(ck, classSels[i]);
        if (m) method_setImplementation(m, (IMP)ck_container);
        else class_addMethod(meta, classSels[i], (IMP)ck_container, "@@:@");
    }
    SEL instSels[] = {
        @selector(initWithContainerIdentifier:),
        NSSelectorFromString(@"initWithContainerID:options:"),
        NSSelectorFromString(@"initWithContainerIdentifier:environment:")
    };
    for (int i = 0; i < 3; i++) {
        Method m = class_getInstanceMethod(ck, instSels[i]);
        if (m) method_setImplementation(m, (IMP)ck_container);
    }
}

#pragma mark - 2. Configuración nativa del motor Dolphin para tvOS (Metal 60fps)

static void configurar_dolphin_para_tvos(void) {
    NSDictionary *valores = @{
        @"pauseButtonIsMenuButton": @YES,
        @"PVDolphinCore.Shader Compilation": @1,       // 1 = Asynchronous (Uber Shaders)
        @"PVDolphinCore.Dual Core": @YES,              // Aprovecha los núcleos de rendimiento del A15
        @"PVDolphinCore.Graphics Backend": @"Metal",   // Metal directo en tvOS
        @"PVDolphinCore.Internal Resolution": @1,      // 1x nativo para mantener 60fps estables sin throttling
        @"PVDolphinCore.V-Sync": @NO,
        @"showFPS": @NO,                               // Sin overlay de emulador
    };

    NSArray *stores = @[
        [NSUserDefaults standardUserDefaults],
        [[NSUserDefaults alloc] initWithSuiteName:@"group.org.provenance-emu.provenance"]
    ];

    for (NSUserDefaults *store in stores) {
        if (!store) continue;
        [valores enumerateKeysAndObjectsUsingBlock:^(NSString *clave, id valor, BOOL *stop) {
            [store setObject:valor forKey:clave];
        }];
        [store synchronize];
    }
}

#pragma mark - 3. Detección y Enlace de ROM en Standalone

static NSString *buscar_rom_embebida(void) {
    NSString *bundlePath = [[NSBundle mainBundle] bundlePath];
    NSArray *subcarpetas = @[@"roms", @"ROMs", @"Game", @""];
    NSArray *extensiones = @[@"iso", @"wbfs", @"rvz", @"gcz", @"ciso", @"gcm"];

    NSFileManager *fm = [NSFileManager defaultManager];
    for (NSString *sub in subcarpetas) {
        NSString *dir = sub.length ? [bundlePath stringByAppendingPathComponent:sub] : bundlePath;
        if (![fm fileExistsAtPath:dir]) continue;

        NSArray *archivos = [fm contentsOfDirectoryAtPath:dir error:nil];
        for (NSString *archivo in archivos) {
            NSString *ext = [archivo pathExtension].lowercaseString;
            if ([extensiones containsObject:ext]) {
                return [dir stringByAppendingPathComponent:archivo];
            }
        }
    }
    return nil;
}

#pragma mark - 4. Desactivación de Servidor Web y Servicios innecesarios

static void desactivar_servidor_web(void) {
    // Si la clase PVWebServer existe, cancelamos su arranque para un juego 100% offline
    Class webServerClass = objc_getClass("PVWebServer");
    if (webServerClass) {
        SEL startSel = NSSelectorFromString(@"startServer");
        Method startMethod = class_getClassMethod(webServerClass, startSel);
        if (startMethod) {
            method_setImplementation(startMethod, (IMP)blackhole_nil);
        }
    }
}

#pragma mark - 5. Auto-arranque Directo del Juego (Zero-UI Launch)

static void arrancar_juego_directo(void) {
    NSString *rom = buscar_rom_embebida();
    NSLog(@"[Standalone] ROM embebida encontrada: %@", rom ?: @"(ninguna)");

    // Leemos metadatos del juego desde game.json si existe
    NSString *gameJsonPath = [[NSBundle mainBundle] pathForResource:@"game" ofType:@"json"];
    NSString *gameMD5 = nil;
    if (gameJsonPath) {
        NSData *data = [NSData dataWithContentsOfFile:gameJsonPath];
        if (data) {
            NSDictionary *info = [NSJSONSerialization JSONObjectWithData:data options:0 error:nil];
            gameMD5 = info[@"md5"];
        }
    }

    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(0.3 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
        id<UIApplicationDelegate> delegate = [UIApplication sharedApplication].delegate;
        if (!delegate) return;

        NSURL *url = nil;
        if (gameMD5.length) {
            url = [NSURL URLWithString:[NSString stringWithFormat:@"provenance://open?md5=%@", gameMD5]];
        } else if (rom) {
            // Si no hay MD5 configurado, usamos URL directa por archivo
            NSString *encoded = [rom stringByAddingPercentEncodingWithAllowedCharacters:[NSCharacterSet URLQueryAllowedCharacterSet]];
            url = [NSURL URLWithString:[NSString stringWithFormat:@"provenance://open?file=%@", encoded]];
        }

        if (url) {
            NSLog(@"[Standalone] Arrancando juego directo con URL: %@", url);
            if ([delegate respondsToSelector:@selector(application:openURL:options:)]) {
                [delegate application:[UIApplication sharedApplication] openURL:url options:@{}];
            }
        }
    });
}

#pragma mark - 6. Constructor de Inicio

__attribute__((constructor)) static void standalone_runner_init(void) {
    NSLog(@"[Standalone] Inicializando Standalone Game Runner para tvOS...");
    desactivar_cloudkit();
    configurar_dolphin_para_tvos();
    desactivar_servidor_web();

    [[NSNotificationCenter defaultCenter] addObserverForName:UIApplicationDidFinishLaunchingNotification
                                                      object:nil
                                                       queue:[NSOperationQueue mainQueue]
                                                  usingBlock:^(NSNotification *note) {
        arrancar_juego_directo();
    }];
}
