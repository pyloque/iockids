IocKids
--
最简单的Java依赖注入框架，区区200行代码，用于学习再合适不过了

功能
--
1. 单例/非单例注入
2. 构造器注入
3. 字段注入
4. 循环依赖注入
5. Qualifier注入
6. 丰富的出错提示

依赖
--
```xml
<dependency>
	<groupId>javax.inject</groupId>
	<artifactId>javax.inject</artifactId>
	<version>1</version>
</dependency>
```

该依赖定义了DI必须实现的基础注解，这也就是JSR-330规范的标准接口协议。JSR330只规定了依赖注入的描述，对于容器实现未作要求。Spring 、Guice 、Dagger这三大DI框架都兼容该协议。

JSR-330
--
@Inject : 标记为“可注入”，相当于Spring里面的AutoWired
@Qualifier : 限定器，用于分门别类，最常用的是名称限定器
@Scope : 标记作用域，最常用的就是单例作用域，扩展里面还有请求作用域、会话作用域等，这不是必须的。
@Named : 基于 String 的限定器，也就是名称限定器
@Singleton : 标记为单例，也就是单例作用域

如需细致了解该规范，请自行google一下

示例
--

```java
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import iockids.Injector;

@Singleton
class Root {

	@Inject
	@Named("a")
	Node a;

	@Inject
	@Named("b")
	Node b;

	@Override
	public String toString() {
		return String.format("root(%s, %s)", a.name(), b.name());
	}

}

interface Node {

	String name();

}

@Singleton
@Named("a")
class NodeA implements Node {

	@Inject
	Leaf leaf;

	@Inject
	@Named("b")
	Node b;

	@Override
	public String name() {
		if (b == null)
			return String.format("nodeA(%s)", leaf);
		else
			return String.format("nodeAWithB(%s)", leaf);
	}

}

@Singleton
@Named("b")
class NodeB implements Node {

	Leaf leaf;

	@Inject
	@Named("a")
	Node a;

	@Inject
	public NodeB(Leaf leaf) {
		this.leaf = leaf;
	}

	@Override
	public String name() {
		if (a == null)
			return String.format("nodeB(%s)", leaf);
		else
			return String.format("nodeBWithA(%s)", leaf);
	}

}

class Leaf {

	@Inject
	Root root;

	int index;

	static int sequence;

	public Leaf() {
		index = sequence++;
	}

	public String toString() {
		if (root == null)
			return "leaf" + index;
		else
			return "leafwithroot" + index;
	}

}

public class Demo {

	public static void main(String[] args) {
		var injector = new Injector();
		injector.registerQualifiedClass(Node.class, NodeA.class);
		injector.registerQualifiedClass(Node.class, NodeB.class);
		var root = injector.getInstance(Root.class);
		System.out.println(root);
	}

}

root(nodeAWithB(leafwithroot0), nodeBWithA(leafwithroot1))
```

Discussion
--
关注公众号「码洞」，和大佬们一起来讨论iockids的设计与实现
