// Sin el entitlement de iCloud, +[CKContainer containerWithIdentifier:] aborta la app.
// Reemplazamos los constructores de CKContainer por un objeto que responde nil/0 a todo.
#import <Foundation/Foundation.h>
#import <objc/runtime.h>

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
- (NSString *)containerIdentifier { return @"iCloud.disabled"; }
@end

static id ck_container(id self, SEL _cmd, ...) {
    static CKBlackHole *hole;
    static dispatch_once_t once;
    dispatch_once(&once, ^{ hole = [CKBlackHole new]; });
    return hole;
}

// Si alguien sube refresh.flag por el servidor web de Provenance, refrescamos la biblioteca.
// En tvOS la raíz de ese servidor es Library/Caches, no Documents.
static void refresh_if_flagged(void) {
    for (NSNumber *dir in @[@(NSCachesDirectory), @(NSDocumentDirectory)]) {
        NSString *base = NSSearchPathForDirectoriesInDomains(dir.unsignedIntegerValue, NSUserDomainMask, YES).firstObject;
        NSString *flag = [base stringByAppendingPathComponent:@"refresh.flag"];
        if (![[NSFileManager defaultManager] fileExistsAtPath:flag]) continue;
        [[NSFileManager defaultManager] removeItemAtPath:flag error:nil];
        NSLog(@"ckstub: refresh.flag encontrado, refrescando biblioteca");
        [[NSNotificationCenter defaultCenter] postNotificationName:@"PVRefreshLibraryNotification" object:nil];
        return;
    }
}

static void start_flag_watch(void) {
    dispatch_source_t t = dispatch_source_create(DISPATCH_SOURCE_TYPE_TIMER, 0, 0, dispatch_get_main_queue());
    dispatch_source_set_timer(t, dispatch_time(DISPATCH_TIME_NOW, 10 * NSEC_PER_SEC), 15 * NSEC_PER_SEC, NSEC_PER_SEC);
    dispatch_source_set_event_handler(t, ^{ refresh_if_flagged(); });
    dispatch_resume(t);
    static dispatch_source_t keep;
    keep = t;
}

// Valores iniciales; si el usuario los cambia desde Ajustes, se respetan.
static void ajustes_iniciales(void) {
    NSDictionary *valores = @{
        @"pauseButtonIsMenuButton": @YES,              // Menú del control abre el menú de pausa
        @"PVDolphinCore.Shader Compilation": @1,       // Asynchronous (Uber): sin tirones al compilar sombreadores
        @"PVDolphinCore.Dual Core": @YES,
    };
    NSArray *stores = @[[NSUserDefaults standardUserDefaults],
                        [[NSUserDefaults alloc] initWithSuiteName:@"group.org.provenance-emu.provenance"]];
    for (NSUserDefaults *d in stores) {
        [valores enumerateKeysAndObjectsUsingBlock:^(NSString *clave, id valor, BOOL *stop) {
            if ([d objectForKey:clave] == nil) [d setObject:valor forKey:clave];
        }];
    }
}

__attribute__((constructor)) static void ckstub_init(void) {
    ajustes_iniciales();
    start_flag_watch();
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
    SEL instSels[] = { @selector(initWithContainerIdentifier:), NSSelectorFromString(@"initWithContainerID:options:"),
                       NSSelectorFromString(@"initWithContainerIdentifier:environment:") };
    for (int i = 0; i < 3; i++) {
        Method m = class_getInstanceMethod(ck, instSels[i]);
        if (m) method_setImplementation(m, (IMP)ck_container);
    }
}
