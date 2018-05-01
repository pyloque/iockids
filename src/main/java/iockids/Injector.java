package iockids;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Qualifier;
import javax.inject.Singleton;

public class Injector {

	// 已经生成的单例实例放在这里，后续注入处可以直接拿
	private Map<Class<?>, Object> singletons = Collections.synchronizedMap(new HashMap<>());
	{
		singletons.put(Injector.class, this);
	}
	// 已经生成的限定器实例放在这里，可续注入处可以直接拿
	// 限定器就是在单例基础上增加一个类别，相当于多种单例，用Annotation来限定具体哪个单例
	private Map<Class<?>, Map<Annotation, Object>> qualifieds = Collections.synchronizedMap(new HashMap<>());

	// 尚未初始化的单例类放在这里
	private Map<Class<?>, Class<?>> singletonClasses = Collections.synchronizedMap(new HashMap<>());
	
	// 尚未初始化的限定类别单例类放在这里
	private Map<Class<?>, Map<Annotation, Class<?>>> qualifiedClasses = Collections.synchronizedMap(new HashMap<>());

	public <T> Injector registerSingleton(Class<T> clazz, T o) {
		if (singletons.put(clazz, o) != null) {
			throw new InjectException("duplicated singleton object for the same class " + clazz.getCanonicalName());
		}
		return this;
	}

	public <T> Injector registerQualified(Class<T> clazz, Annotation anno, T o) {
		if (!anno.annotationType().isAnnotationPresent(Qualifier.class)) {
			throw new InjectException(
					"annotation must be decorated with Qualifier " + anno.annotationType().getCanonicalName());
		}
		var os = qualifieds.get(clazz);
		if (os == null) {
			os = Collections.synchronizedMap(new HashMap<>());
			qualifieds.put(clazz, os);
		}
		if (os.put(anno, o) != null) {
			throw new InjectException(
					String.format("duplicated qualified object with the same qualifier %s with the class %s",
							anno.annotationType().getCanonicalName(), clazz.getCanonicalName()));
		}
		return this;
	}

	public <T> Injector registerSingletonClass(Class<T> clazz) {
		return this.registerSingletonClass(clazz, clazz);
	}

	public <T> Injector registerSingletonClass(Class<?> parentType, Class<T> clazz) {
		if (singletonClasses.put(parentType, clazz) != null) {
			throw new InjectException("duplicated singleton class " + parentType.getCanonicalName());
		}
		return this;
	}

	public <T> Injector registerQualifiedClass(Class<?> parentType, Class<T> clazz) {
		for (Annotation anno : clazz.getAnnotations()) {
			if (anno.annotationType().isAnnotationPresent(Qualifier.class)) {
				return this.registerQualifiedClass(parentType, anno, clazz);
			}
		}
		throw new InjectException("class should decorated with annotation tagged by Qualifier");
	}

	public <T> Injector registerQualifiedClass(Class<?> parentType, Annotation anno, Class<T> clazz) {
		if (!anno.annotationType().isAnnotationPresent(Qualifier.class)) {
			throw new InjectException(
					"annotation must be decorated with Qualifier " + anno.annotationType().getCanonicalName());
		}
		var annos = qualifiedClasses.get(parentType);
		if (annos == null) {
			annos = Collections.synchronizedMap(new HashMap<>());
			qualifiedClasses.put(parentType, annos);
		}
		if (annos.put(anno, clazz) != null) {
			throw new InjectException(String.format("duplicated qualifier %s with the same class %s",
					anno.annotationType().getCanonicalName(), parentType.getCanonicalName()));
		}
		return this;
	}

	public <T> T createNew(Class<T> clazz) {
		return this.createNew(clazz, null);
	}

	@SuppressWarnings("unchecked")
	public <T> T createNew(Class<T> clazz, Consumer<T> consumer) {
		var o = singletons.get(clazz);
		if (o != null) {
			return (T) o;
		}

		var cons = new ArrayList<Constructor<T>>();
		T target = null;
		for (var con : clazz.getDeclaredConstructors()) {
			// 默认构造期不需要Inject注解
			if (!con.isAnnotationPresent(Inject.class) && con.getParameterCount() > 0) {
				continue;
			}
			if (!con.trySetAccessible()) {
				continue;
			}
			cons.add((Constructor<T>) con);
		}
		if (cons.size() > 1) {
			throw new InjectException("dupcated constructor for injection class " + clazz.getCanonicalName());
		}
		if (cons.size() == 0) {
			throw new InjectException("no accessible constructor for injection class " + clazz.getCanonicalName());
		}

		target = createFromConstructor(cons.get(0)); // 构造器注入

		var isSingleton = clazz.isAnnotationPresent(Singleton.class);
		if (!isSingleton) {
			isSingleton = this.singletonClasses.containsKey(clazz);
		}
		if (isSingleton) {
			singletons.put(clazz, target);
		}
		if (consumer != null) {
			consumer.accept(target);
		}

		injectMembers(target);

		return target;
	}

	private <T> T createFromConstructor(Constructor<T> con) {
		var params = new Object[con.getParameterCount()];
		var i = 0;
		for (Parameter parameter : con.getParameters()) {
			var param = createFromParameter(parameter);
			if (param == null) {
				throw new InjectException(String.format("parameter should not be empty with name %s of class %s",
						parameter.getName(), con.getDeclaringClass().getCanonicalName()));
			}
			params[i++] = param;
		}
		try {
			return con.newInstance(params);
		} catch (Exception e) {
			throw new InjectException("create instance from constructor error", e);
		}
	}

	@SuppressWarnings("unchecked")
	private <T> T createFromParameter(Parameter parameter) {
		var clazz = parameter.getType();
		T t = createFromQualified(parameter.getDeclaringExecutable().getDeclaringClass(), clazz,
				parameter.getAnnotations());
		if (t != null) {
			return t;
		}
		return (T) createNew(clazz);
	}

	@SuppressWarnings("unchecked")
	private <T> T createFromField(Field field) {
		var clazz = field.getType();
		T t = createFromQualified(field.getDeclaringClass(), field.getType(), field.getAnnotations());
		if (t != null) {
			return t;
		}
		return (T) createNew(clazz);
	}

	@SuppressWarnings("unchecked")
	private <T> T createFromQualified(Class<?> declaringClazz, Class<?> clazz, Annotation[] annos) {
		var qs = qualifieds.get(clazz);
		if (qs != null) {
			Set<Object> os = new HashSet<>();
			for (var anno : annos) {
				var o = qs.get(anno);
				if (o != null) {
					os.add(o);
				}
			}
			if (os.size() > 1) {
				throw new InjectException(String.format("duplicated qualified object for field %s@%s",
						clazz.getCanonicalName(), declaringClazz.getCanonicalName()));
			}
			if (!os.isEmpty()) {
				return (T) (os.iterator().next());
			}
		}
		var qz = qualifiedClasses.get(clazz);
		if (qz != null) {
			Set<Class<?>> oz = new HashSet<>();
			Annotation annoz = null;
			for (var anno : annos) {
				var z = qz.get(anno);
				if (z != null) {
					oz.add(z);
					annoz = anno;
				}
			}
			if (oz.size() > 1) {
				throw new InjectException(String.format("duplicated qualified classes for field %s@%s",
						clazz.getCanonicalName(), declaringClazz.getCanonicalName()));
			}
			if (!oz.isEmpty()) {
				final var annozRead = annoz;
				var t = (T) createNew(oz.iterator().next(), (o) -> {
					this.registerQualified((Class<T>) clazz, annozRead, (T) o);
				});
				return t;
			}
		}
		return null;
	}

	/**
	 * 注入成员
	 * @param t
	 */
	public <T> void injectMembers(T t) {
		List<Field> fields = new ArrayList<>();
		for (Field field : t.getClass().getDeclaredFields()) {
			if (field.isAnnotationPresent(Inject.class) && field.trySetAccessible()) {
				fields.add(field);
			}
		}
		for (Field field : fields) {
			Object f = createFromField(field);
			try {
				field.set(t, f);
			} catch (Exception e) {
				throw new InjectException(
						String.format("set field for %s@%s error", t.getClass().getCanonicalName(), field.getName()),
						e);
			}
		}
	}

	/**
	 * 获取对象
	 * @param clazz
	 * @return
	 */
	public <T> T getInstance(Class<T> clazz) {
		return createNew(clazz);
	}
}
